package com.example.newmusic.module.play.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.cache.CacheService;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.play.dto.PlaySettingReq;
import com.example.newmusic.module.play.entity.UserPlaySetting;
import com.example.newmusic.module.play.mapper.UserPlaySettingMapper;
import com.example.newmusic.module.play.support.PlaySettingSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 播放设置服务（模块 12 FR-21-008）。
 *
 * <p>两级缓存：L1 Caffeine + L2 Redis {@code ml:play:setting:{userId}}，TTL 7 天。
 * 设置行不存在时返回默认值（登录用户默认 high、自动降质开、倍速 1.0），首次 PUT 才落库。
 *
 * <p>音质偏好另外写一份 {@code ml:play:quality:{userId}}（永久），
 * PRD BR-21-002 对这个键有明确要求；读路径仍以设置行为准，Redis 键供播放链路快速取值。
 */
@Slf4j
@Service
public class PlaySettingService {

    private static final Duration SETTING_TTL = Duration.ofDays(7);

    private final UserPlaySettingMapper settingMapper;
    private final CacheService cacheService;
    private final RedisOps redisOps;

    public PlaySettingService(UserPlaySettingMapper settingMapper,
                              CacheService cacheService,
                              RedisOps redisOps) {
        this.settingMapper = settingMapper;
        this.cacheService = cacheService;
        this.redisOps = redisOps;
    }

    /** 读取设置；不存在返回默认值并缓存，避免每次请求都打库。 */
    public UserPlaySetting get(Long userId) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        String key = RedisKeys.playSetting(userId);
        CacheService.Lookup<UserPlaySetting> lookup = cacheService.get(key, UserPlaySetting.class);
        if (lookup.isHit()) {
            return lookup.value();
        }
        if (lookup.isNegative()) {
            return UserPlaySetting.defaults(userId);
        }

        UserPlaySetting setting = selectByUser(userId);
        if (setting == null) {
            setting = UserPlaySetting.defaults(userId);
        }
        cacheService.put(key, setting, SETTING_TTL);
        syncQualityKey(userId, setting.getDefaultQuality());
        return setting;
    }

    /**
     * 局部更新设置。只改请求里非空的字段（与模块 10 的设置接口一致），
     * 非法值由 {@link PlaySettingSupport#apply} 抛 30061。
     */
    @Transactional
    public UserPlaySetting update(Long userId, PlaySettingReq req) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        UserPlaySetting setting = selectByUser(userId);
        if (setting == null) {
            setting = UserPlaySetting.defaults(userId);
        }
        PlaySettingSupport.apply(setting, req);
        if (setting.getId() == null) {
            settingMapper.insert(setting);
        } else {
            settingMapper.updateById(setting);
        }

        String key = RedisKeys.playSetting(userId);
        // 先删再写：Redis 写失败时至少 L1 不会被旧值继续服务
        cacheService.evict(key);
        cacheService.put(key, setting, SETTING_TTL);
        syncQualityKey(userId, setting.getDefaultQuality());
        log.info("播放设置已更新 userId={} quality={} rate={} autoQuality={}",
                userId, setting.getDefaultQuality(), setting.getPlaybackRate(), setting.getAutoQuality());
        return setting;
    }

    /** 登录用户的默认音质；游客/无设置时为 standard。 */
    public String defaultQuality(Long userId) {
        if (userId == null) {
            return PlaySettingSupport.QUALITY_STANDARD;
        }
        try {
            return get(userId).getDefaultQuality();
        } catch (RuntimeException e) {
            // 设置读取失败不能阻断播放：退到「登录用户 high」
            log.warn("读取播放设置失败，使用默认音质 userId={} cause={}", userId, e.toString());
            return PlaySettingSupport.QUALITY_HIGH;
        }
    }

    private UserPlaySetting selectByUser(Long userId) {
        return settingMapper.selectOne(new LambdaQueryWrapper<UserPlaySetting>()
                .eq(UserPlaySetting::getUserId, userId)
                .last("LIMIT 1"));
    }

    private void syncQualityKey(Long userId, String quality) {
        if (quality != null) {
            redisOps.set(RedisKeys.playQuality(userId), quality, null);
        }
    }
}
