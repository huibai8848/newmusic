package com.example.newmusic.module.socialupgrade.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.socialupgrade.config.SocialUpgradeProperties;
import com.example.newmusic.module.socialupgrade.entity.UserProfile;
import com.example.newmusic.module.socialupgrade.entity.UserRelationScore;
import com.example.newmusic.module.socialupgrade.mapper.UserProfileMapper;
import com.example.newmusic.module.socialupgrade.mapper.UserRelationScoreMapper;
import com.example.newmusic.module.socialupgrade.vo.IntimacyVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 亲密度（FR-25-011）。
 *
 * <p>写路径按“先衰减、再累加”结算，读路径再补一次从最后互动到现在的衰减；
 * 两条路径的指数相同，因此重复衰减不会改变结果（e<sup>-λt</sup> 可乘性）。
 *
 * <p>防刷（BR-25-125）只限制“计入次数”，不做业务拦截：超过每日 10 次后
 * 互动本身仍然成功，只是不再加亲密度。
 */
@Slf4j
@Service
public class IntimacyService {

    public static final int WEIGHT_MESSAGE = 1;
    public static final int WEIGHT_COMMENT = 3;
    public static final int WEIGHT_LIKE = 1;
    public static final int WEIGHT_REPOST = 2;
    public static final int WEIGHT_MENTION = 3;
    public static final int WEIGHT_ROOM = 2;
    public static final int WEIGHT_MUTUAL = 10;

    public static final int LEVEL_STRANGER = 0;
    public static final int LEVEL_KNOWN = 1;
    public static final int LEVEL_FAMILIAR = 2;
    public static final int LEVEL_FRIEND = 3;
    public static final int LEVEL_CLOSE = 4;

    private static final Map<Integer, String> LEVEL_NAMES = new LinkedHashMap<>();

    static {
        LEVEL_NAMES.put(LEVEL_STRANGER, "陌生");
        LEVEL_NAMES.put(LEVEL_KNOWN, "认识");
        LEVEL_NAMES.put(LEVEL_FAMILIAR, "熟悉");
        LEVEL_NAMES.put(LEVEL_FRIEND, "好友");
        LEVEL_NAMES.put(LEVEL_CLOSE, "密友");
    }

    private final UserRelationScoreMapper scoreMapper;
    private final UserProfileMapper profileMapper;
    private final SocialUserSupport users;
    private final RedisOps redisOps;
    private final SocialUpgradeProperties properties;

    public IntimacyService(UserRelationScoreMapper scoreMapper, UserProfileMapper profileMapper,
                           SocialUserSupport users, RedisOps redisOps,
                           SocialUpgradeProperties properties) {
        this.scoreMapper = scoreMapper;
        this.profileMapper = profileMapper;
        this.users = users;
        this.redisOps = redisOps;
        this.properties = properties;
    }

    /**
     * 记录一次互动。任何异常都吞掉：亲密度是激励数据，不能影响点赞/评论/私信主流程。
     */
    public void record(Long userId, Long targetId, int weight) {
        if (userId == null || targetId == null || userId.equals(targetId) || weight <= 0) {
            return;
        }
        try {
            int cap = Math.max(1, properties.getIntimacy().getDailyCapPerTarget());
            String key = RedisKeys.intimacyDaily(userId, targetId,
                    LocalDate.now(ZoneId.systemDefault()).toString());
            long count = redisOps.increment(key, Duration.ofDays(2));
            if (count > cap) {
                return;
            }
            scoreMapper.upsertDecayed(IdWorker.getId(), userId, targetId, weight,
                    properties.getIntimacy().getDecayLambda(), LocalDateTime.now());
            redisOps.delete(RedisKeys.relationScore(userId, targetId));
        } catch (Exception e) {
            log.debug("亲密度写入失败 userId={} targetId={}", userId, targetId, e);
        }
    }

    /**
     * 查询 viewer 与 target 的亲密度。只有当事人双方可见（BR-25-119）。
     *
     * <p>target 关闭“展示分数”时仍返回等级，但 score 置空（BR-25-126）。
     */
    public IntimacyVO get(Long viewerId, Long targetId) {
        if (viewerId == null) {
            throw new BizException(ResultCode.NO_PERMISSION);
        }
        if (viewerId.equals(targetId)) {
            throw new BizException(ResultCode.PARAM_INVALID, "不能查询与自己的亲密度");
        }
        users.requireUser(targetId);

        IntimacyVO vo = new IntimacyVO();
        vo.setUserId(viewerId);
        vo.setTargetUserId(targetId);

        UserRelationScore row = scoreMapper.selectPair(viewerId, targetId);
        int score = row == null ? 0 : decayed(row.getScore(), row.getLastInteractTime());
        // 互相关注但还没有任何互动时，基础分 10 仍然成立
        if (row == null && users.isMutual(viewerId, targetId)) {
            score = WEIGHT_MUTUAL;
        }
        vo.setScore(score);
        int level = levelOf(score);
        vo.setLevel(level);
        vo.setLevelName(levelName(level));
        vo.setScoreVisible(scoreVisible(targetId));
        if (!Boolean.TRUE.equals(vo.getScoreVisible())) {
            vo.setScore(null);
        }
        return vo;
    }

    /** 累计分（未衰减）转等级，供后台/测试直接调用。 */
    public static int levelOf(int score) {
        if (score >= 1000) {
            return LEVEL_CLOSE;
        }
        if (score >= 200) {
            return LEVEL_FRIEND;
        }
        if (score >= 50) {
            return LEVEL_FAMILIAR;
        }
        if (score >= 10) {
            return LEVEL_KNOWN;
        }
        return LEVEL_STRANGER;
    }

    public static String levelName(int level) {
        return LEVEL_NAMES.getOrDefault(level, "陌生");
    }

    private boolean scoreVisible(Long targetId) {
        UserProfile profile = profileMapper.selectById(targetId);
        if (profile == null || !StringUtils.hasText(profile.getPrivacyJson())) {
            return true;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(profile.getPrivacyJson());
            return !(node.has("intimacyScore") && node.get("intimacyScore").asInt(0) == 1);
        } catch (Exception e) {
            return true;
        }
    }

    private int decayed(Integer stored, LocalDateTime lastInteract) {
        if (stored == null || stored <= 0) {
            return 0;
        }
        if (lastInteract == null) {
            return stored;
        }
        long seconds = ChronoUnit.SECONDS.between(lastInteract, LocalDateTime.now());
        double days = Math.max(0, seconds) / 86400.0;
        double value = stored * Math.exp(-properties.getIntimacy().getDecayLambda() * days);
        return (int) Math.round(value);
    }
}
