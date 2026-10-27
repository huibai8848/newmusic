package com.example.newmusic.module.ops.service;

import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.ops.dto.DegradeSwitchVO;
import com.example.newmusic.module.ops.entity.DegradeEvent;
import com.example.newmusic.module.ops.entity.DegradeSwitch;
import com.example.newmusic.module.ops.mapper.DegradeEventMapper;
import com.example.newmusic.module.ops.mapper.DegradeSwitchMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 五级降级（FR-28-003 / AC-28-003-1~8）。
 *
 * <p>「一键降级」不是关掉一个全局标志，而是把每个开关的 {@code degrade_level} 与目标等级比较后
 * 批量改写 {@code enabled}：等级 ≥ 开关阈值 → 关闭。这样每个功能仍然可以在后台单独开关，
 * 一键降级只负责把它们推到该等级应有的状态（AC-28-003-3）。
 *
 * <p>开关缓存 2 秒刷新一次（AC-28-003-2 的「秒级生效」）；读不到时**默认开启**
 * （EX-28-003-2：宁可功能开着，也不要因为读配置失败把全站关掉）。
 */
@Slf4j
@Service
public class DegradeService {

    public static final String SWITCH_SEARCH_SUGGEST = "feature.search.suggest";
    public static final String SWITCH_SEARCH_HOT = "feature.search.hot";
    public static final String SWITCH_AI = "feature.ai";
    public static final String SWITCH_RECOMMEND = "feature.recommend";
    public static final String SWITCH_ROOM_GIFT = "feature.room.gift";
    public static final String SWITCH_TRACKING = "feature.tracking";
    public static final String SWITCH_UPLOAD = "feature.upload";
    public static final String SWITCH_COMMENT = "feature.social.comment";
    public static final String SWITCH_POST = "feature.social.post";
    public static final String SWITCH_ROOM_CHAT = "feature.room.chat";
    public static final String SWITCH_MESSAGE = "feature.social.message";
    public static final String SWITCH_WRITE = "feature.write";
    public static final String SWITCH_PROFILE = "feature.user.profile";
    public static final String SWITCH_PLAYLIST = "feature.playlist";
    public static final String SWITCH_REPORT = "feature.report";
    public static final String SWITCH_SHARE = "feature.share";
    public static final String SWITCH_REGISTER = "feature.user.register";
    public static final String SWITCH_SAFETY = "feature.safety";

    private static final String[] LEVEL_NAMES = {
            "正常", "轻度降级", "中度降级", "重度降级", "保命降级", "只读"
    };

    private final DegradeSwitchMapper switchMapper;
    private final DegradeEventMapper eventMapper;
    private final OpsConfigService configService;

    private final Map<String, DegradeSwitch> cache = new ConcurrentHashMap<>();
    // 0 而非 Long.MIN_VALUE：后者参与 now - loadedAt 会溢出，首次刷新条件永远不成立
    private volatile long loadedAt = 0L;

    public DegradeService(DegradeSwitchMapper switchMapper, DegradeEventMapper eventMapper,
                          OpsConfigService configService) {
        this.switchMapper = switchMapper;
        this.eventMapper = eventMapper;
        this.configService = configService;
    }

    @Scheduled(fixedDelayString = "${newmusic.ops.degrade-refresh-interval-ms:1000}")
    public void scheduledRefresh() {
        long interval = 2000L;
        if (System.currentTimeMillis() - loadedAt >= interval) {
            refresh();
        }
    }

    public synchronized void refresh() {
        try {
            List<DegradeSwitch> rows = switchMapper.selectList(null);
            Map<String, DegradeSwitch> latest = new ConcurrentHashMap<>();
            if (rows != null) {
                for (DegradeSwitch row : rows) {
                    latest.put(row.getSwitchKey(), row);
                }
            }
            cache.clear();
            cache.putAll(latest);
        } catch (Exception e) {
            log.warn("降级开关加载失败，沿用上一份 cache={} cause={}", cache.size(), e.toString());
        } finally {
            loadedAt = System.currentTimeMillis();
        }
    }

    /** 当前等级（0~5）。 */
    public int currentLevel() {
        return configService.degradeLevel();
    }

    public String currentLevelName() {
        return levelName(currentLevel());
    }

    public static String levelName(int level) {
        return level >= 0 && level < LEVEL_NAMES.length ? LEVEL_NAMES[level] : "未知";
    }

    public boolean isEnabled(String switchKey) {
        ensureFresh();
        DegradeSwitch item = cache.get(switchKey);
        // 读不到 = 保守开启（EX-28-003-2）
        return item == null || item.getEnabled() == null || item.getEnabled() == 1;
    }

    public List<DegradeSwitch> switches() {
        ensureFresh();
        List<DegradeSwitch> list = new ArrayList<>(cache.values());
        list.sort((a, b) -> {
            int la = a.getDegradeLevel() == null ? 99 : a.getDegradeLevel();
            int lb = b.getDegradeLevel() == null ? 99 : b.getDegradeLevel();
            if (la != lb) {
                return Integer.compare(la, lb);
            }
            return String.valueOf(a.getSwitchKey()).compareTo(String.valueOf(b.getSwitchKey()));
        });
        return list;
    }

    public List<DegradeSwitchVO> switchViews() {
        List<DegradeSwitchVO> list = new ArrayList<>();
        int level = currentLevel();
        for (DegradeSwitch item : switches()) {
            DegradeSwitchVO vo = new DegradeSwitchVO();
            vo.setSwitchKey(item.getSwitchKey());
            vo.setSwitchName(item.getSwitchName());
            vo.setEnabled(item.getEnabled());
            vo.setDegradeLevel(item.getDegradeLevel());
            vo.setDescription(item.getDescription());
            vo.setOperatorId(item.getOperatorId());
            vo.setVersion(item.getVersion());
            vo.setUpdateTime(item.getUpdateTime());
            vo.setActiveAtCurrentLevel(item.getEnabled() != null && item.getEnabled() == 0);
            vo.setCurrentLevel(level);
            list.add(vo);
        }
        return list;
    }

    /**
     * 一键降级（AC-28-003-3）。
     *
     * @return 实际变更的开关数量
     */
    @Transactional
    public int setLevel(int level, String reason) {
        if (level < 0 || level > 5) {
            throw new BizException(ResultCode.OPS_DEGRADE_LEVEL_INVALID);
        }
        LoginUser operator = UserContext.get();
        Long operatorId = operator == null ? null : operator.getUserId();
        int from = currentLevel();
        if (from == level) {
            log.info("降级等级未变化 level={}，仅记录操作", level);
        }
        int changed = switchMapper.disableUpTo(level, operatorId)
                + switchMapper.enableAbove(level, operatorId);
        configService.set(OpsConfigService.KEY_DEGRADE_LEVEL, String.valueOf(level), operatorId,
                "一键降级 " + from + " → " + level + (StringUtils.hasText(reason) ? "：" + reason : ""));

        DegradeEvent event = new DegradeEvent();
        event.setFromLevel(from);
        event.setToLevel(level);
        event.setSwitchesChanged(changed);
        event.setReason(reason);
        event.setOperatorId(operatorId);
        event.setOperatorName(operator == null ? "system"
                : (StringUtils.hasText(operator.getNickname()) ? operator.getNickname() : operator.getUsername()));
        event.setCreateTime(LocalDateTime.now());
        eventMapper.insert(event);
        refresh();
        log.warn("一键降级 from={} to={} switchesChanged={} operator={} reason={}",
                from, level, changed, operatorId, reason);
        return changed;
    }

    @Transactional
    public void setSwitch(String switchKey, boolean enabled, String remark) {
        DegradeSwitch item = switchMapper.selectById(switchKey);
        if (item == null) {
            throw new BizException(ResultCode.OPS_SWITCH_NOT_FOUND);
        }
        item.setEnabled(enabled ? 1 : 0);
        item.setOperatorId(UserContext.getUserId());
        item.setVersion(item.getVersion() == null ? 1 : item.getVersion() + 1);
        item.setUpdateTime(LocalDateTime.now());
        if (StringUtils.hasText(remark)) {
            item.setDescription(remark);
        }
        switchMapper.updateById(item);
        refresh();
        log.warn("降级开关变更 key={} enabled={} operator={}", switchKey, enabled, UserContext.getUserId());
    }

    /**
     * 请求守卫：命中的功能开关关闭时抛 {@link ResultCode#SERVICE_DEGRADED}。
     *
     * <p>这里**不做业务降级**（例如推荐关闭后回退热门榜）——那是各业务自己的兜底路径；
     * 这个守卫的作用是「开关关了就绝不再进入业务」，让降级真实可验证。
     */
    public void guard(String method, String path) {
        String switchKey = switchFor(method, path);
        if (switchKey == null) {
            return;
        }
        if (!isEnabled(switchKey)) {
            int level = currentLevel();
            writeLevelHeader(level);
            throw new BizException(ResultCode.SERVICE_DEGRADED,
                    "该功能正在维护（" + levelName(level) + "），请稍后再试");
        }
    }

    /**
     * HTTP 路径 → 降级开关。
     *
     * <p>返回 null 表示不拦截。读接口在 1~4 级仍然可用；只有写接口会落到
     * {@code feature.write} 这个 5 级兜底开关。
     */
    public static String switchFor(String method, String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        if (path.startsWith("/api/v1/admin/") || path.startsWith("/actuator")
                || path.startsWith("/media/") || path.startsWith("/api/v1/internal/")
                || path.equals("/api/v1/system") || path.startsWith("/api/v1/system/")) {
            return null;
        }
        // 登录/刷新/登出是恢复入口，任何等级都必须保留（否则降级后无法登录处理）
        if (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/refresh")
                || path.equals("/api/v1/auth/logout")) {
            return null;
        }
        if (path.startsWith("/api/v1/audit/")) {
            return SWITCH_SAFETY;
        }
        if (path.equals("/api/v1/auth/register")) {
            return SWITCH_REGISTER;
        }
        // 推荐与 AI 的读接口同样受 2 级降级影响（个性化推荐 → 热门、AI 生成 → 关闭）
        if (path.startsWith("/api/v1/recommend/")) {
            return SWITCH_RECOMMEND;
        }
        if (path.startsWith("/api/v1/ai/")) {
            return SWITCH_AI;
        }
        if (isRead(method)) {
            if (path.startsWith("/api/v1/search/suggest")) {
                return SWITCH_SEARCH_SUGGEST;
            }
            if (path.startsWith("/api/v1/search/hot")) {
                return SWITCH_SEARCH_HOT;
            }
            // 其他读接口不因 level 2/3 被拦截：前端需要数据来渲染「热门兜底」
            return null;
        }
        // 播放上报是核心数据，降级也不能丢（BR-28-028）
        if (path.startsWith("/api/v1/play/record") || path.startsWith("/api/v1/play/end")
                || path.startsWith("/api/v1/track/")) {
            return path.startsWith("/api/v1/track/") ? SWITCH_TRACKING : null;
        }
        if (path.startsWith("/api/v1/rooms/") && path.contains("/gift")) {
            return SWITCH_ROOM_GIFT;
        }
        if (path.startsWith("/api/v1/upload/")) {
            return SWITCH_UPLOAD;
        }
        if (path.startsWith("/api/v1/comments")) {
            return SWITCH_COMMENT;
        }
        if (path.startsWith("/api/v1/posts")) {
            return SWITCH_POST;
        }
        if (path.startsWith("/api/v1/messages") || path.startsWith("/api/v1/conversations")) {
            return SWITCH_MESSAGE;
        }
        if (path.startsWith("/api/v1/users/")) {
            return SWITCH_PROFILE;
        }
        if (path.startsWith("/api/v1/playlists")) {
            return SWITCH_PLAYLIST;
        }
        if (path.startsWith("/api/v1/reports")) {
            return SWITCH_REPORT;
        }
        if (path.startsWith("/api/v1/share")) {
            return SWITCH_SHARE;
        }
        return SWITCH_WRITE;
    }

    private static boolean isRead(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    /** C 端只暴露「等级 + 关闭了哪些功能」，不暴露开关的阈值与操作人。 */
    public Map<String, Object> publicStatus() {
        int level = currentLevel();
        List<String> disabled = new ArrayList<>();
        for (DegradeSwitch item : switches()) {
            if (item.getEnabled() != null && item.getEnabled() == 0) {
                disabled.add(item.getSwitchKey());
            }
        }
        Collections.sort(disabled);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("level", level);
        map.put("levelName", levelName(level));
        map.put("degraded", level > 0);
        map.put("disabledFeatures", disabled);
        map.put("banner", level == 0 ? null
                : "系统当前处于" + levelName(level) + "，部分功能暂不可用");
        map.put("updatedAt", System.currentTimeMillis());
        return map;
    }

    private void writeLevelHeader(int level) {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return;
        }
        HttpServletResponse response = attributes.getResponse();
        if (response != null) {
            response.setHeader("X-Degrade-Level", String.valueOf(level));
        }
    }

    private void ensureFresh() {
        if (System.currentTimeMillis() - loadedAt >= 2000L) {
            refresh();
        }
    }
}
