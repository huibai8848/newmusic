package com.example.newmusic.module.socialupgrade.support;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 私信营销检测（BR-25-117）。
 *
 * <p>同一发送者一天内把相同内容发给超过 {@value #SAME_CONTENT_LIMIT} 个会话，
 * 判定为批量营销，限制私信 7 天。只做内容指纹计数，不保存正文。
 */
@Component
public class MessageSpamGuard {

    private static final int SAME_CONTENT_LIMIT = 10;
    private static final int RESTRICT_DAYS = 7;

    private final RedisOps redisOps;

    public MessageSpamGuard(RedisOps redisOps) {
        this.redisOps = redisOps;
    }

    /** 发信前检查是否仍在限制期。 */
    public void check(Long senderId) {
        if (senderId == null) {
            return;
        }
        if (redisOps.get(RedisKeys.messageSpamRestrict(senderId)).isPresent()) {
            throw new BizException(ResultCode.MESSAGE_SPAM_RESTRICTED);
        }
    }

    /**
     * 发信后记录“该内容发到了哪个会话”。按**会话数**而不是条数判定：
     * 同一句话在一对一聊天里重复 11 次是正常聊天，群发到 11 个会话才是营销。
     */
    public void record(Long senderId, Long conversationId, String content) {
        if (senderId == null || conversationId == null || !StringUtils.hasText(content)) {
            return;
        }
        String date = LocalDate.now(ZoneId.systemDefault()).toString();
        String fingerprint = fingerprint(content);
        String key = RedisKeys.messageSpamFingerprint(senderId, fingerprint, date);
        redisOps.addToSet(key, String.valueOf(conversationId), Duration.ofDays(2));
        if (redisOps.members(key).size() > SAME_CONTENT_LIMIT) {
            redisOps.set(RedisKeys.messageSpamRestrict(senderId), "1", Duration.ofDays(RESTRICT_DAYS));
        }
    }

    private static String fingerprint(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(content.trim().hashCode());
        }
    }
}
