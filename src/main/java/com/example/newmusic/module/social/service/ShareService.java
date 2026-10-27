package com.example.newmusic.module.social.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.social.config.SocialProperties;
import com.example.newmusic.module.social.dto.ShareReq;
import com.example.newmusic.module.social.entity.ShareLink;
import com.example.newmusic.module.social.mapper.ShareMapper;
import com.example.newmusic.module.social.support.SocialTargets;
import com.example.newmusic.module.social.vo.ShareVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 分享短链（模块 10 FR-19-007 / 系分 D-09）。
 *
 * <p>MySQL 是权威（t_share，短码唯一索引），Redis 只存一份带 TTL 的映射做快速跳转。
 * Redis 不可用时仍能从库里解析，短链不会失效。
 */
@Slf4j
@Service
public class ShareService {

    /** Base62 去掉易混淆字符 0/O/1/l/I 后的 57 个字符（BR-19-068）。 */
    private static final char[] ALPHABET =
            "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    private static final Set<String> CHANNELS = new HashSet<>(Arrays.asList(
            "COPY_LINK", "SYSTEM", "IN_APP", "THIRD_PARTY", "OTHER"));

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShareMapper shareMapper;
    private final SocialTargets targets;
    private final RedisOps redisOps;
    private final SocialProperties properties;

    public ShareService(ShareMapper shareMapper, SocialTargets targets,
                        RedisOps redisOps, SocialProperties properties) {
        this.shareMapper = shareMapper;
        this.targets = targets;
        this.redisOps = redisOps;
        this.properties = properties;
    }

    public ShareVO create(Long userId, ShareReq req) {
        String type = targets.requireShareType(req.getTargetType());
        SocialTargets.TargetInfo info = targets.require(type, req.getTargetId(), true);
        String channel = normalizeChannel(req.getChannel());

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expire = now.plusDays(properties.getShare().getTtlDays());

        ShareLink link = null;
        for (int attempt = 0; attempt < 5 && link == null; attempt++) {
            ShareLink candidate = new ShareLink();
            candidate.setId(IdWorker.getId());
            candidate.setCode(randomCode());
            candidate.setTargetType(type);
            candidate.setTargetId(info.getTargetId());
            candidate.setSharerId(userId);
            candidate.setChannel(channel);
            candidate.setClickCount(0);
            candidate.setExpireTime(expire);
            try {
                shareMapper.insert(candidate);
                link = candidate;
            } catch (DuplicateKeyException e) {
                // 短码碰撞：换一个重试（BR-19-067）
            }
        }
        if (link == null) {
            throw new BizException(ResultCode.DUPLICATE_DATA, "短链生成失败，请稍后重试");
        }

        redisOps.set(RedisKeys.socialShort(link.getCode()), cacheValue(link),
                Duration.ofDays(Math.max(1, properties.getShare().getTtlDays())));
        targets.addShareCount(type, info.getTargetId(), 1);
        return toVO(link);
    }

    /**
     * 解析短码并记一次点击。
     *
     * @return 重定向地址；短码不存在/过期返回 null
     */
    public String resolve(String code, String visitor) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        ShareLink link = fromCache(code);
        if (link == null) {
            link = shareMapper.selectOne(new QueryWrapper<ShareLink>().eq("code", code));
            if (link == null) {
                return null;
            }
        }
        if (link.getExpireTime() != null && link.getExpireTime().isBefore(LocalDateTime.now())) {
            redisOps.delete(RedisKeys.socialShort(code));
            return null;
        }

        countClick(code, link, visitor);
        return targetUrl(link);
    }

    /** 短链不要暴露过多信息，统计接口只给点击数。 */
    public long clickCount(String code) {
        ShareLink link = shareMapper.selectOne(new QueryWrapper<ShareLink>().eq("code", code));
        if (link == null) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "短链不存在或已过期");
        }
        String cached = redisOps.get(RedisKeys.socialShortClick(code)).orElse(null);
        long redisClicks = cached == null ? 0L : parseLong(cached);
        return (link.getClickCount() == null ? 0L : link.getClickCount()) + redisClicks;
    }

    // ------------------------------------------------------------------ helpers

    private static String normalizeChannel(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "COPY_LINK";
        }
        String channel = raw.trim().toUpperCase(Locale.ROOT);
        return CHANNELS.contains(channel) ? channel : "OTHER";
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** 缓存值：{@code type|targetId|sharerId|expireEpochMilli}，避免一个小对象也要 JSON 解析。 */
    private static String cacheValue(ShareLink link) {
        long expire = link.getExpireTime() == null ? 0L
                : link.getExpireTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return link.getTargetType() + '|' + link.getTargetId() + '|'
                + (link.getSharerId() == null ? "" : link.getSharerId()) + '|' + expire;
    }

    private ShareLink fromCache(String code) {
        String value = redisOps.get(RedisKeys.socialShort(code)).orElse(null);
        if (value == null) {
            return null;
        }
        String[] parts = value.split("\\|", -1);
        if (parts.length != 4) {
            return null;
        }
        try {
            ShareLink link = new ShareLink();
            link.setCode(code);
            link.setTargetType(parts[0]);
            link.setTargetId(Long.valueOf(parts[1]));
            link.setSharerId(parts[2].isEmpty() ? null : Long.valueOf(parts[2]));
            long expire = parseLong(parts[3]);
            link.setExpireTime(expire <= 0 ? null : LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(expire), ZoneId.systemDefault()));
            return link;
        } catch (Exception e) {
            return null;
        }
    }

    private void countClick(String code, ShareLink link, String visitor) {
        try {
            shareMapper.incrementClick(code);
        } catch (Exception e) {
            log.warn("短链点击落库失败 code={} cause={}", code, e.toString());
        }
        redisOps.increment(RedisKeys.socialShortClick(code),
                Duration.ofDays(Math.max(1, properties.getShare().getTtlDays())));
        if (visitor != null && !visitor.isEmpty()) {
            String key = RedisKeys.socialShortVisitor(code);
            long before = redisOps.setSize(key);
            redisOps.addToSet(key, visitor,
                    Duration.ofDays(Math.max(1, properties.getShare().getTtlDays())));
            if (redisOps.setSize(key) > before) {
                log.debug("短链独立访客 code={} visitor={}", code, visitor);
            }
        }
    }

    private String targetUrl(ShareLink link) {
        String base = properties.getShare().getWebBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path;
        switch (link.getTargetType()) {
            case SocialTargets.SONG:
                path = "/song/";
                break;
            case SocialTargets.PLAYLIST:
                path = "/playlist/";
                break;
            case SocialTargets.ALBUM:
                path = "/album/";
                break;
            default:
                path = "/";
                break;
        }
        StringBuilder url = new StringBuilder(base).append(path).append(link.getTargetId())
                .append("?from=share");
        if (link.getSharerId() != null) {
            url.append("&sharerId=").append(link.getSharerId());
        }
        return url.toString();
    }

    private ShareVO toVO(ShareLink link) {
        ShareVO vo = new ShareVO();
        vo.setCode(link.getCode());
        vo.setShortUrl(properties.getShare().getShortUrlPrefix() + link.getCode());
        vo.setTargetUrl(targetUrl(link));
        vo.setExpireTime(link.getExpireTime());
        return vo;
    }

    private static long parseLong(String value) {
        try {
            return value == null || value.isEmpty() ? 0L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
