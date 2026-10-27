package com.example.newmusic.module.contentsafety.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.contentsafety.dto.MinorConfigReq;
import com.example.newmusic.module.contentsafety.dto.MinorModeReq;
import com.example.newmusic.module.contentsafety.entity.MinorProtection;
import com.example.newmusic.module.contentsafety.mapper.MinorProtectionMapper;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 未成年人保护（FR-27-009）。
 *
 * <p>三个容易踩错的点：
 * <ol>
 *   <li>「无法识别年龄」不等于「成年人」。没有配置、也没有生日时按保护态处理营销/位置，
 *       但不强制把账号锁进青少年模式，否则整个存量用户都会被禁用社交。</li>
 *   <li>未成年人账号的青少年模式不可关闭（BR-27-103），关闭必须先过密码。</li>
 *   <li>禁用时段允许跨零点（22:00-08:00），判断时不能只写 start &lt;= now &lt; end。</li>
 * </ol>
 */
@Slf4j
@Service
public class MinorProtectionService {

    /** 默认每日 40 分钟。 */
    public static final int DEFAULT_DAILY_LIMIT = 40;
    public static final LocalTime DEFAULT_DISABLE_START = LocalTime.of(22, 0);
    public static final LocalTime DEFAULT_DISABLE_END = LocalTime.of(8, 0);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final MinorProtectionMapper minorMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final RedisOps redisOps;

    public MinorProtectionService(MinorProtectionMapper minorMapper, UserMapper userMapper,
                                  PasswordEncoder passwordEncoder, RedisOps redisOps) {
        this.minorMapper = minorMapper;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.redisOps = redisOps;
    }

    // ------------------------------------------------------------------ config

    public MinorProtection config(Long userId) {
        MinorProtection row = minorMapper.selectById(userId);
        return row == null ? defaults(userId) : row;
    }

    /** 管理端配置。密码不出现在返回值里。 */
    @Transactional
    public MinorProtection saveConfig(Long userId, MinorConfigReq req) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.ACCOUNT_NOT_FOUND);
        }
        MinorProtection row = minorMapper.selectById(userId);
        MinorProtection update = new MinorProtection();
        update.setUserId(userId);
        update.setIsMinor(req.getIsMinor() != null ? (req.getIsMinor() ? 1 : 0)
                : (row != null && row.getIsMinor() != null ? row.getIsMinor()
                : (isMinorByBirthday(user) ? 1 : 0)));
        int mode = req.getMode() != null ? (req.getMode() ? 1 : 0)
                : (row != null && row.getMode() != null ? row.getMode() : update.getIsMinor());
        if (update.getIsMinor() == 1) {
            // 未成年人账号强制进入青少年模式，管理端也不能关。
            mode = 1;
        }
        update.setMode(mode);
        update.setDailyLimitMinutes(clampMinutes(req.getDailyLimitMinutes(),
                row == null ? DEFAULT_DAILY_LIMIT : row.getDailyLimitMinutes()));
        update.setDisableStart(req.getDisableStartHour() != null
                ? LocalTime.of(req.getDisableStartHour(), 0)
                : (row != null && row.getDisableStart() != null
                ? row.getDisableStart() : DEFAULT_DISABLE_START));
        update.setDisableEnd(req.getDisableEndHour() != null
                ? LocalTime.of(req.getDisableEndHour(), 0)
                : (row != null && row.getDisableEnd() != null
                ? row.getDisableEnd() : DEFAULT_DISABLE_END));
        if (req.getGuardianUserId() != null) {
            if (req.getGuardianUserId().equals(userId)) {
                throw new BizException(ResultCode.PARAM_INVALID, "监护人不能是自己");
            }
            if (userMapper.selectById(req.getGuardianUserId()) == null) {
                throw new BizException(ResultCode.ACCOUNT_NOT_FOUND, "监护人不存在");
            }
            update.setGuardianUserId(req.getGuardianUserId());
        } else if (row != null) {
            update.setGuardianUserId(row.getGuardianUserId());
        }
        update.setModePassword(row == null ? null : row.getModePassword());
        update.setCreateTime(row == null ? LocalDateTime.now() : row.getCreateTime());
        update.setUpdateTime(LocalDateTime.now());
        if (row == null) {
            minorMapper.insert(update);
        } else {
            minorMapper.updateById(update);
        }
        return config(userId);
    }

    // ------------------------------------------------------------------ teen mode

    /**
     * 开启/关闭青少年模式。
     *
     * @param operatorId 当前登录用户
     */
    @Transactional
    public Map<String, Object> toggleMode(Long operatorId, MinorModeReq req) {
        Long targetId = req.getUserId() == null ? operatorId : req.getUserId();
        MinorProtection row = minorMapper.selectById(targetId);
        User user = userMapper.selectById(targetId);
        if (user == null) {
            throw new BizException(ResultCode.ACCOUNT_NOT_FOUND);
        }
        boolean self = targetId.equals(operatorId);
        if (!self) {
            boolean guardian = row != null && operatorId.equals(row.getGuardianUserId());
            if (!guardian) {
                throw new BizException(ResultCode.MINOR_GUARDIAN_REQUIRED);
            }
        }
        boolean enabled = Boolean.TRUE.equals(req.getEnabled());
        if (!enabled) {
            boolean explicitMinor = row != null && Integer.valueOf(1).equals(row.getIsMinor());
            boolean bornMinor = isMinorByBirthday(user);
            if (explicitMinor || bornMinor) {
                throw new BizException(ResultCode.MINOR_MODE_NOT_CLOSABLE);
            }
        }
        String password = req.getPassword();
        String stored = row == null ? null : row.getModePassword();
        if (!StringUtils.hasText(password)) {
            throw new BizException(ResultCode.MINOR_MODE_PASSWORD_ERROR, "请输入青少年模式密码");
        }
        if (stored != null) {
            if (!passwordEncoder.matches(password, stored)) {
                throw new BizException(ResultCode.MINOR_MODE_PASSWORD_ERROR);
            }
        } else if (password.trim().length() < 4) {
            throw new BizException(ResultCode.PARAM_INVALID, "密码至少 4 位");
        }

        MinorProtection update = new MinorProtection();
        update.setUserId(targetId);
        update.setMode(enabled ? 1 : 0);
        if (stored == null) {
            update.setModePassword(passwordEncoder.encode(password.trim()));
        }
        update.setIsMinor(row != null && row.getIsMinor() != null ? row.getIsMinor()
                : (isMinorByBirthday(user) ? 1 : 0));
        update.setDailyLimitMinutes(row != null && row.getDailyLimitMinutes() != null
                ? row.getDailyLimitMinutes() : DEFAULT_DAILY_LIMIT);
        update.setDisableStart(row != null && row.getDisableStart() != null
                ? row.getDisableStart() : DEFAULT_DISABLE_START);
        update.setDisableEnd(row != null && row.getDisableEnd() != null
                ? row.getDisableEnd() : DEFAULT_DISABLE_END);
        update.setGuardianUserId(row == null ? null : row.getGuardianUserId());
        update.setCreateTime(row == null ? LocalDateTime.now() : row.getCreateTime());
        update.setUpdateTime(LocalDateTime.now());
        if (row == null) {
            minorMapper.insert(update);
        } else {
            minorMapper.updateById(update);
        }
        log.info("青少年模式切换 userId={} enabled={} operatorId={}", targetId, enabled, operatorId);
        return status(targetId);
    }

    public Map<String, Object> status(Long userId) {
        MinorProtection row = minorMapper.selectById(userId);
        User user = userMapper.selectById(userId);
        boolean unknown = row == null || row.getIsMinor() == null;
        boolean minor = row != null && Integer.valueOf(1).equals(row.getIsMinor());
        if (unknown && user != null) {
            minor = isMinorByBirthday(user);
            unknown = !hasBirthday(user);
        }
        boolean mode = row != null && Integer.valueOf(1).equals(row.getMode());
        boolean protectedUser = minor || unknown;
        int limit = row != null && row.getDailyLimitMinutes() != null
                ? row.getDailyLimitMinutes() : DEFAULT_DAILY_LIMIT;
        LocalTime start = row != null && row.getDisableStart() != null
                ? row.getDisableStart() : DEFAULT_DISABLE_START;
        LocalTime end = row != null && row.getDisableEnd() != null
                ? row.getDisableEnd() : DEFAULT_DISABLE_END;
        int used = usedMinutes(userId);
        boolean inWindow = inDisableWindow(LocalTime.now(), start, end);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", String.valueOf(userId));
        out.put("minor", minor);
        out.put("ageUnknown", unknown);
        out.put("defaultProtected", protectedUser);
        out.put("mode", mode);
        out.put("forced", minor);
        out.put("passwordSet", row != null && row.getModePassword() != null);
        out.put("dailyLimitMinutes", limit);
        out.put("usedMinutes", used);
        out.put("remainingMinutes", Math.max(0, limit - used));
        out.put("disableStart", start.format(TIME));
        out.put("disableEnd", end.format(TIME));
        out.put("inDisableWindow", inWindow);
        out.put("allowedNow", !mode || (!inWindow && used < limit));
        out.put("socialAllowed", !mode);
        out.put("marketingAllowed", !protectedUser);
        out.put("locationAllowed", !protectedUser);
        out.put("guardianUserId", row == null || row.getGuardianUserId() == null ? null
                : String.valueOf(row.getGuardianUserId()));
        return out;
    }

    /** 播放/浏览等基础访问的心跳。每次最多计 1 分钟，前端约每分钟调一次。 */
    public Map<String, Object> heartbeat(Long userId) {
        requireAllowed(userId);
        String date = LocalDate.now().format(DATE);
        String minute = LocalDateTime.now().format(MINUTE);
        if (redisOps.setIfAbsent(RedisKeys.minorTick(userId, minute), "1", Duration.ofSeconds(90))) {
            redisOps.increment(RedisKeys.minorUsage(userId, date), ttlToMidnight());
        }
        return status(userId);
    }

    /** 使用前置校验：时段 + 时长。 */
    public void requireAllowed(Long userId) {
        MinorProtection row = minorMapper.selectById(userId);
        if (row == null || !Integer.valueOf(1).equals(row.getMode())) {
            return;
        }
        LocalTime start = row.getDisableStart() == null ? DEFAULT_DISABLE_START : row.getDisableStart();
        LocalTime end = row.getDisableEnd() == null ? DEFAULT_DISABLE_END : row.getDisableEnd();
        if (inDisableWindow(LocalTime.now(), start, end)) {
            throw new BizException(ResultCode.MINOR_MODE_TIME_FORBIDDEN,
                    "青少年模式 " + start.format(TIME) + "-" + end.format(TIME) + " 不可使用");
        }
        int limit = row.getDailyLimitMinutes() == null ? DEFAULT_DAILY_LIMIT : row.getDailyLimitMinutes();
        if (usedMinutes(userId) >= limit) {
            throw new BizException(ResultCode.MINOR_MODE_USAGE_LIMIT);
        }
    }

    /** 评论/私信/关注等社交能力：青少年模式下一律不可用。 */
    public void requireSocialAllowed(Long userId) {
        requireAllowed(userId);
        MinorProtection row = minorMapper.selectById(userId);
        if (row != null && Integer.valueOf(1).equals(row.getMode())) {
            throw new BizException(ResultCode.MINOR_MODE_FEATURE_FORBIDDEN);
        }
    }

    public boolean canReceiveMarketing(Long userId) {
        return !protectedUser(userId);
    }

    public boolean canCollectLocation(Long userId) {
        return !protectedUser(userId);
    }

    // ------------------------------------------------------------------ guardian

    @Transactional
    public MinorProtection bindGuardian(Long minorUserId, Long guardianUserId) {
        if (minorUserId.equals(guardianUserId)) {
            throw new BizException(ResultCode.PARAM_INVALID, "监护人不能是自己");
        }
        User guardian = userMapper.selectById(guardianUserId);
        if (guardian == null) {
            throw new BizException(ResultCode.ACCOUNT_NOT_FOUND, "监护人不存在");
        }
        if (protectedUser(guardianUserId)) {
            throw new BizException(ResultCode.PARAM_INVALID, "监护人必须是成年人");
        }
        MinorProtection row = minorMapper.selectById(minorUserId);
        MinorProtection update = new MinorProtection();
        update.setUserId(minorUserId);
        update.setGuardianUserId(guardianUserId);
        update.setIsMinor(row != null && row.getIsMinor() != null ? row.getIsMinor() : 1);
        update.setMode(row != null && row.getMode() != null ? row.getMode() : 1);
        update.setDailyLimitMinutes(row != null && row.getDailyLimitMinutes() != null
                ? row.getDailyLimitMinutes() : DEFAULT_DAILY_LIMIT);
        update.setDisableStart(row != null && row.getDisableStart() != null
                ? row.getDisableStart() : DEFAULT_DISABLE_START);
        update.setDisableEnd(row != null && row.getDisableEnd() != null
                ? row.getDisableEnd() : DEFAULT_DISABLE_END);
        update.setModePassword(row == null ? null : row.getModePassword());
        update.setCreateTime(row == null ? LocalDateTime.now() : row.getCreateTime());
        update.setUpdateTime(LocalDateTime.now());
        if (row == null) {
            minorMapper.insert(update);
        } else {
            minorMapper.updateById(update);
        }
        return config(minorUserId);
    }

    public List<Map<String, Object>> wards(Long guardianId) {
        List<MinorProtection> rows = minorMapper.selectList(new QueryWrapper<MinorProtection>()
                .eq("guardian_user_id", guardianId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (MinorProtection row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", String.valueOf(row.getUserId()));
            item.put("mode", row.getMode());
            item.put("dailyLimitMinutes", row.getDailyLimitMinutes());
            item.put("usedMinutes", usedMinutes(row.getUserId()));
            User user = userMapper.selectById(row.getUserId());
            item.put("nickname", user == null ? null : user.getNickname());
            out.add(item);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    public int usedMinutes(Long userId) {
        String value = redisOps.get(RedisKeys.minorUsage(userId, LocalDate.now().format(DATE)))
                .orElse("0");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean inDisableWindow(LocalTime now, LocalTime start, LocalTime end) {
        if (start == null || end == null || start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        // 跨零点，如 22:00 - 08:00
        return !now.isBefore(start) || now.isBefore(end);
    }

    private boolean protectedUser(Long userId) {
        MinorProtection row = minorMapper.selectById(userId);
        if (row != null && row.getIsMinor() != null) {
            return row.getIsMinor() == 1;
        }
        User user = userMapper.selectById(userId);
        // 无配置 + 无生日：无法识别年龄，按保护态处理（营销/位置），但不强锁模式。
        return user == null || !hasBirthday(user) || isMinorByBirthday(user);
    }

    private static boolean hasBirthday(User user) {
        return user.getBirthday() != null;
    }

    private static boolean isMinorByBirthday(User user) {
        if (user == null || user.getBirthday() == null) {
            return false;
        }
        return Period.between(user.getBirthday(), LocalDate.now()).getYears() < 18;
    }

    private static int clampMinutes(Integer value, Integer fallback) {
        int v = value == null ? (fallback == null ? DEFAULT_DAILY_LIMIT : fallback) : value;
        return Math.max(10, Math.min(v, 120));
    }

    private static MinorProtection defaults(Long userId) {
        MinorProtection row = new MinorProtection();
        row.setUserId(userId);
        row.setMode(0);
        row.setDailyLimitMinutes(DEFAULT_DAILY_LIMIT);
        row.setDisableStart(DEFAULT_DISABLE_START);
        row.setDisableEnd(DEFAULT_DISABLE_END);
        return row;
    }

    private static Duration ttlToMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return Duration.between(now, midnight);
    }
}
