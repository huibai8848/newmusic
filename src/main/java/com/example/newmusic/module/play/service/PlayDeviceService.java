package com.example.newmusic.module.play.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.play.dto.DeviceVO;
import com.example.newmusic.module.play.entity.UserDevice;
import com.example.newmusic.module.play.mapper.UserDeviceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 用户设备登记与设备管理（模块 12 FR-21-007 / BR-21-071）。
 *
 * <p>DB 是设备列表的权威数据源；Redis ZSet {@code ml:device:{userId}} 只做「最近活跃」
 * 的快速视图，也方便后续做「同账号同时在线设备数」限制。
 *
 * <p><b>设备登出的语义边界</b>：本模块只把设备标记为「已登出」并从列表移除。
 * 当前 JWT 不是按设备签发的，无法只吊销某一台设备的 accessToken；真正让
 * 旧设备失去访问能力依赖令牌过期或账号级改密（模块 01 已有）。这是与 PRD
 * 字面语义的偏差，已在系分 12 记录。
 */
@Slf4j
@Service
public class PlayDeviceService {

    private static final int MAX_DEVICES = 50;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_LOGGED_OUT = 2;

    private final UserDeviceMapper deviceMapper;
    private final RedisOps redisOps;

    public PlayDeviceService(UserDeviceMapper deviceMapper, RedisOps redisOps) {
        this.deviceMapper = deviceMapper;
        this.redisOps = redisOps;
    }

    /**
     * 登记/刷新设备活跃时间。登录用户首次播放或上报进度时调用。
     *
     * <p>失败不影响主流程：设备记录是旁路数据，不能因为一次唯一键竞争让播放上报 500。
     */
    public void touch(Long userId, String deviceId, String deviceName, String deviceType, String ip) {
        if (userId == null || !StringUtils.hasText(deviceId)) {
            return;
        }
        String id = truncate(deviceId.trim(), 64);
        String name = StringUtils.hasText(deviceName) ? truncate(deviceName.trim(), 128) : null;
        String type = normalizeType(deviceType);
        String clientIp = StringUtils.hasText(ip) ? truncate(ip.trim(), 64) : null;
        LocalDateTime now = LocalDateTime.now();

        try {
            UserDevice existing = find(userId, id);
            if (existing == null) {
                UserDevice device = new UserDevice();
                device.setUserId(userId);
                device.setDeviceId(id);
                device.setDeviceName(name);
                device.setDeviceType(type);
                device.setLastActiveTime(now);
                device.setLastIp(clientIp);
                device.setStatus(STATUS_ACTIVE);
                try {
                    deviceMapper.insert(device);
                } catch (DuplicateKeyException race) {
                    // 并发首播：另一个请求先插入了，退化为更新
                    existing = find(userId, id);
                    if (existing != null) {
                        updateActive(existing, name, type, clientIp, now);
                    }
                }
            } else {
                updateActive(existing, name, type, clientIp, now);
            }
            redisOps.zAdd(RedisKeys.device(userId), id, System.currentTimeMillis(), null);
        } catch (RuntimeException e) {
            log.warn("设备登记失败 userId={} deviceId={} cause={}", userId, id, e.toString());
        }
    }

    public List<DeviceVO> list(Long userId, String currentDeviceId) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        List<UserDevice> rows = deviceMapper.selectList(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getUserId, userId)
                .eq(UserDevice::getStatus, STATUS_ACTIVE)
                .orderByDesc(UserDevice::getLastActiveTime)
                .last("LIMIT " + MAX_DEVICES));
        String current = currentDeviceId == null ? null : currentDeviceId.trim();
        List<DeviceVO> result = new ArrayList<>(rows.size());
        for (UserDevice row : rows) {
            DeviceVO vo = new DeviceVO();
            vo.setDeviceId(row.getDeviceId());
            vo.setDeviceName(row.getDeviceName());
            vo.setDeviceType(row.getDeviceType());
            vo.setLastActiveTime(row.getLastActiveTime());
            vo.setLastIp(row.getLastIp());
            vo.setCurrent(current != null && current.equals(row.getDeviceId()));
            result.add(vo);
        }
        return result.isEmpty() ? Collections.<DeviceVO>emptyList() : result;
    }

    /** 登出一台设备；不存在或已登出返回 30062（重复点击也给出明确结果）。 */
    @Transactional
    public void logout(Long userId, String deviceId) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        if (!StringUtils.hasText(deviceId)) {
            throw new BizException(ResultCode.PARAM_INVALID, "deviceId 不能为空");
        }
        String id = truncate(deviceId.trim(), 64);
        UserDevice device = find(userId, id);
        if (device == null || device.getStatus() == null || device.getStatus() != STATUS_ACTIVE) {
            throw new BizException(ResultCode.PLAY_DEVICE_NOT_FOUND);
        }
        device.setStatus(STATUS_LOGGED_OUT);
        deviceMapper.updateById(device);
        redisOps.zRemove(RedisKeys.device(userId), id);
    }

    /** 登出全部设备，返回受影响台数。 */
    @Transactional
    public int logoutAll(Long userId) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        UserDevice patch = new UserDevice();
        patch.setStatus(STATUS_LOGGED_OUT);
        int updated = deviceMapper.update(patch, new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getUserId, userId)
                .eq(UserDevice::getStatus, STATUS_ACTIVE));
        redisOps.delete(RedisKeys.device(userId));
        return updated;
    }

    private UserDevice find(Long userId, String deviceId) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getUserId, userId)
                .eq(UserDevice::getDeviceId, deviceId)
                .last("LIMIT 1"));
    }

    private void updateActive(UserDevice device, String name, String type, String ip, LocalDateTime now) {
        device.setLastActiveTime(now);
        device.setStatus(STATUS_ACTIVE);
        if (name != null) {
            device.setDeviceName(name);
        }
        if (type != null) {
            device.setDeviceType(type);
        }
        if (ip != null) {
            device.setLastIp(ip);
        }
        deviceMapper.updateById(device);
    }

    private static String normalizeType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "WEB";
        }
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if ("PWA".equals(value) || "MOBILE_WEB".equals(value) || "WEB".equals(value)) {
            return value;
        }
        return "WEB";
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
