package com.example.newmusic.module.dataplatform.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.dataplatform.config.DataPlatformProperties;
import com.example.newmusic.module.dataplatform.entity.UserProfileTag;
import com.example.newmusic.module.dataplatform.mapper.ProfileQueryMapper;
import com.example.newmusic.module.dataplatform.mapper.UserProfileTagMapper;
import com.example.newmusic.module.dataplatform.model.ChannelRow;
import com.example.newmusic.module.dataplatform.model.DwsAggRow;
import com.example.newmusic.module.dataplatform.model.FirstActiveRow;
import com.example.newmusic.module.dataplatform.model.HistoryAggRow;
import com.example.newmusic.module.dataplatform.model.HourAggRow;
import com.example.newmusic.module.dataplatform.model.LastActiveRow;
import com.example.newmusic.module.dataplatform.model.LatestEnvRow;
import com.example.newmusic.module.dataplatform.model.ProfileUserRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户画像标签（FR-26-004）。
 *
 * <p>10 类标签：基础属性 / 设备属性 / 行为属性 / 音乐偏好 / 消费能力 / 社交属性 /
 * 生命周期 / 价值分层 / 兴趣标签 / 渠道来源。全部 T+1 重算（BR-26-038），
 * 标签值里不出现手机号、邮箱、精确地址等敏感信息（BR-26-041）。
 *
 * <p>重算采用「先算完一个用户的全部标签，再删旧标签 + upsert」：
 * 单用户失败不会污染其它用户；{@code update_date < 本次日期} 的旧标签会被清掉，
 * 避免「上周的兴趣标签」永远残留。
 */
@Slf4j
@Service
public class ProfileTagService {

    /** 标签目录（后台筛选与自助查看共用）。 */
    public static final List<Map<String, String>> CATALOG = buildCatalog();

    private final ProfileQueryMapper profileMapper;
    private final UserProfileTagMapper tagMapper;
    private final DataPlatformProperties properties;

    public ProfileTagService(ProfileQueryMapper profileMapper, UserProfileTagMapper tagMapper,
                             DataPlatformProperties properties) {
        this.profileMapper = profileMapper;
        this.tagMapper = tagMapper;
        this.properties = properties;
    }

    // ================================================================== 重算

    /** 全量重算（定时任务 / 后台手工触发）。 */
    public Map<String, Object> recomputeAll(String dateText) {
        LocalDate date = parseDate(dateText, LocalDate.now());
        long begin = System.currentTimeMillis();
        int batch = Math.max(100, properties.getProfileBatchUsers());
        String version = tagVersion(date);

        Map<Long, DwsAggRow> dws = new HashMap<>();
        for (DwsAggRow row : profileMapper.profileDws(date.minusDays(29), date)) {
            dws.put(row.getUserId(), row);
        }
        Map<Long, Map<String, Map<String, Long>>> history = loadHistory(date);
        Map<Long, Map<Integer, Long>> hours = new HashMap<>();
        for (HourAggRow row : profileMapper.profileHours(date.minusDays(29), 500000)) {
            if (row.getUserId() != null && row.getHour() != null) {
                hours.computeIfAbsent(row.getUserId(), k -> new HashMap<>())
                        .merge(row.getHour(), value(row.getCnt()), Long::sum);
            }
        }
        Map<Long, LatestEnvRow> envs = new HashMap<>();
        for (LatestEnvRow row : profileMapper.profileLatestEnv(date.minusDays(29))) {
            envs.put(row.getUserId(), row);
        }
        Map<Long, String> channels = new HashMap<>();
        for (ChannelRow row : profileMapper.profileRegisterChannels()) {
            channels.put(row.getUserId(), row.getChannel());
        }
        Map<Long, LocalDate> firstActive = new HashMap<>();
        for (FirstActiveRow row : profileMapper.profileFirstActive()) {
            firstActive.put(row.getUserId(), row.getFirstDt());
        }
        Map<Long, LocalDate> lastActive = new HashMap<>();
        for (LastActiveRow row : profileMapper.profileLastActive()) {
            lastActive.put(row.getUserId(), row.getLastDt());
        }

        long cursor = 0L;
        int users = 0;
        int tags = 0;
        while (true) {
            List<ProfileUserRow> page = profileMapper.profileUsers(cursor, batch);
            if (page.isEmpty()) {
                break;
            }
            for (ProfileUserRow user : page) {
                cursor = Math.max(cursor, user.getUserId());
                List<UserProfileTag> list = buildTags(user, date, version,
                        dws.get(user.getUserId()), history.get(user.getUserId()), hours.get(user.getUserId()),
                        envs.get(user.getUserId()), channels.get(user.getUserId()),
                        firstActive.get(user.getUserId()), lastActive.get(user.getUserId()));
                saveUserTags(user.getUserId(), date, list);
                users++;
                tags += list.size();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("version", version);
        result.put("users", users);
        result.put("tags", tags);
        result.put("costMs", System.currentTimeMillis() - begin);
        result.put("categories", CATALOG.size());
        return result;
    }

    /** 单用户重算（后台查看画像前的手工刷新）。 */
    public List<UserProfileTag> recomputeUser(Long userId, String dateText) {
        if (userId == null || userId <= 0) {
            throw new BizException(ResultCode.PARAM_INVALID, "userId 不合法");
        }
        LocalDate date = parseDate(dateText, LocalDate.now());
        String version = tagVersion(date);
        ProfileUserRow user = null;
        long cursor = userId - 1;
        for (ProfileUserRow row : profileMapper.profileUsers(cursor, 1)) {
            if (userId.equals(row.getUserId())) {
                user = row;
            }
        }
        if (user == null) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "用户不存在或已注销");
        }
        Map<Long, DwsAggRow> dws = new HashMap<>();
        for (DwsAggRow row : profileMapper.profileDws(date.minusDays(29), date)) {
            dws.put(row.getUserId(), row);
        }
        Map<Long, Map<String, Map<String, Long>>> history = loadHistory(date);
        Map<Long, Map<Integer, Long>> hours = new HashMap<>();
        for (HourAggRow row : profileMapper.profileHours(date.minusDays(29), 500000)) {
            if (row.getUserId() != null) {
                hours.computeIfAbsent(row.getUserId(), k -> new HashMap<>())
                        .merge(row.getHour() == null ? 0 : row.getHour(), value(row.getCnt()), Long::sum);
            }
        }
        Map<Long, LatestEnvRow> envs = new HashMap<>();
        for (LatestEnvRow row : profileMapper.profileLatestEnv(date.minusDays(29))) {
            envs.put(row.getUserId(), row);
        }
        Map<Long, String> channels = new HashMap<>();
        for (ChannelRow row : profileMapper.profileRegisterChannels()) {
            channels.put(row.getUserId(), row.getChannel());
        }
        Map<Long, LocalDate> firstActive = new HashMap<>();
        for (FirstActiveRow row : profileMapper.profileFirstActive()) {
            firstActive.put(row.getUserId(), row.getFirstDt());
        }
        Map<Long, LocalDate> lastActive = new HashMap<>();
        for (LastActiveRow row : profileMapper.profileLastActive()) {
            lastActive.put(row.getUserId(), row.getLastDt());
        }
        List<UserProfileTag> tags = buildTags(user, date, version, dws.get(userId),
                history.get(userId), hours.get(userId), envs.get(userId), channels.get(userId),
                firstActive.get(userId), lastActive.get(userId));
        saveUserTags(userId, date, tags);
        return tags;
    }

    // ================================================================== 查询

    /** 用户自助查看（BR-26-046）。 */
    public Map<String, Object> view(Long userId, boolean refresh) {
        if (userId == null || userId <= 0) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        List<UserProfileTag> tags;
        if (refresh) {
            tags = recomputeUser(userId, null);
        } else {
            tags = tagMapper.selectList(Wrappers.<UserProfileTag>lambdaQuery()
                    .eq(UserProfileTag::getUserId, userId)
                    .orderByAsc(UserProfileTag::getTagType, UserProfileTag::getTagName));
            if (tags.isEmpty()) {
                tags = recomputeUser(userId, null);
            }
        }
        return render(userId, tags);
    }

    /** 管理端查看（不做敏感字段过滤以外的裁剪：画像本身就不存敏感信息）。 */
    public Map<String, Object> adminView(Long userId, String dateText) {
        if (userId == null || userId <= 0) {
            throw new BizException(ResultCode.PARAM_INVALID, "userId 不合法");
        }
        LocalDate date = parseDate(dateText, null);
        List<UserProfileTag> tags;
        if (date != null) {
            tags = tagMapper.selectList(Wrappers.<UserProfileTag>lambdaQuery()
                    .eq(UserProfileTag::getUserId, userId)
                    .eq(UserProfileTag::getUpdateDate, date)
                    .orderByAsc(UserProfileTag::getTagType, UserProfileTag::getTagName));
        } else {
            tags = tagMapper.selectList(Wrappers.<UserProfileTag>lambdaQuery()
                    .eq(UserProfileTag::getUserId, userId)
                    .orderByAsc(UserProfileTag::getTagType, UserProfileTag::getTagName));
        }
        return render(userId, tags);
    }

    private Map<String, Object> render(Long userId, List<UserProfileTag> tags) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (UserProfileTag tag : tags) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tagName", tag.getTagName());
            item.put("tagValue", tag.getTagValue());
            item.put("version", tag.getTagVersion());
            item.put("updateDate", tag.getUpdateDate() == null ? null : tag.getUpdateDate().toString());
            grouped.computeIfAbsent(tag.getTagType(), k -> new ArrayList<>()).add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", String.valueOf(userId));
        result.put("categories", grouped);
        result.put("catalog", CATALOG);
        result.put("notice", "画像仅用于个性化推荐与产品分析，不含手机号、邮箱、精确位置等敏感信息；"
                + "你可以在设置中关闭个性化推荐。");
        return result;
    }

    // ================================================================== 标签构造

    List<UserProfileTag> buildTags(ProfileUserRow user, LocalDate date, String version,
                                   DwsAggRow dws, Map<String, Map<String, Long>> history,
                                   Map<Integer, Long> hours, LatestEnvRow env, String channel,
                                   LocalDate firstActive, LocalDate lastActive) {
        List<UserProfileTag> tags = new ArrayList<>(18);
        long userId = user.getUserId();
        LocalDate registerDate = user.getCreateTime() == null ? null : user.getCreateTime().toLocalDate();
        int registerDays = registerDate == null ? 0 : (int) ChronoUnit.DAYS.between(registerDate, date);
        long activeDays = dws == null ? 0 : value(dws.getActiveDays());
        long playCnt = dws == null ? 0 : value(dws.getPlayCnt());
        long playDuration = dws == null ? 0 : value(dws.getPlayDuration());
        long interactCnt = dws == null ? 0 : value(dws.getInteractCnt());
        long roomCnt = dws == null ? 0 : value(dws.getRoomCnt());

        // 1. 基础属性
        add(tags, userId, "基础属性", "gender", gender(user.getGender()), version, date);
        add(tags, userId, "基础属性", "age_range", ageRange(user.getBirthday(), date), version, date);
        add(tags, userId, "基础属性", "region",
                user.getRegion() == null || user.getRegion().isEmpty() ? "未知" : user.getRegion(), version, date);
        add(tags, userId, "基础属性", "register_days", String.valueOf(registerDays), version, date);
        add(tags, userId, "基础属性", "vip_level",
                user.getVipLevel() == null || user.getVipLevel() == 0 ? "普通用户"
                        : ("VIP" + user.getVipLevel()), version, date);

        // 2. 设备属性
        add(tags, userId, "设备属性", "platform",
                env == null || blank(env.getPlatform()) ? "未知" : env.getPlatform(), version, date);
        add(tags, userId, "设备属性", "os",
                env == null || blank(env.getOs()) ? "未知" : env.getOs(), version, date);
        add(tags, userId, "设备属性", "network",
                env == null || blank(env.getNetwork()) ? "未知" : env.getNetwork(), version, date);

        // 3. 行为属性
        add(tags, userId, "行为属性", "activity_level", activityLevel(activeDays), version, date);
        add(tags, userId, "行为属性", "active_hour", activeHour(hours), version, date);
        long avgMinutes = activeDays == 0 ? 0 : playDuration / activeDays / 60;
        add(tags, userId, "行为属性", "avg_play_minutes", String.valueOf(avgMinutes), version, date);

        // 4. 音乐偏好
        add(tags, userId, "音乐偏好", "top_genre", top(history, "genre", "未知"), version, date);
        add(tags, userId, "音乐偏好", "top_artist", top(history, "artist", "未知"), version, date);
        add(tags, userId, "音乐偏好", "top_language", top(history, "language", "未知"), version, date);

        // 5. 消费能力（本期简化为使用深度）
        add(tags, userId, "消费能力", "use_depth", useDepth(playCnt), version, date);

        // 6. 社交属性
        add(tags, userId, "社交属性", "social_level", socialLevel(interactCnt), version, date);
        add(tags, userId, "社交属性", "follow_level", followLevel(user.getFollowCount()), version, date);

        // 7. 生命周期
        add(tags, userId, "生命周期", "lifecycle",
                lifecycle(registerDays, activeDays, lastActive, date), version, date);

        // 8. 价值分层
        add(tags, userId, "价值分层", "rfm_segment",
                rfm(lastActive, date, activeDays, playCnt), version, date);

        // 9. 兴趣标签
        add(tags, userId, "兴趣标签", "interest", interest(history, hours), version, date);

        // 10. 渠道来源
        add(tags, userId, "渠道来源", "register_channel",
                blank(channel) ? "未知" : channel, version, date);
        return tags;
    }

    private void saveUserTags(Long userId, LocalDate date, List<UserProfileTag> tags) {
        profileMapper.deleteStaleTags(userId, date);
        if (tags.isEmpty()) {
            return;
        }
        // 分批写，避免单条 SQL 过长
        int batch = 50;
        for (int i = 0; i < tags.size(); i += batch) {
            profileMapper.upsertTags(tags.subList(i, Math.min(tags.size(), i + batch)));
        }
    }

    // ================================================================== 取值规则

    private static String gender(Integer gender) {
        if (gender == null) {
            return "未知";
        }
        return gender == 1 ? "男" : (gender == 2 ? "女" : "未知");
    }

    static String ageRange(LocalDate birthday, LocalDate date) {
        if (birthday == null) {
            return "未知";
        }
        int age = Period.between(birthday, date).getYears();
        if (age < 0 || age > 120) {
            return "未知";
        }
        if (age < 18) {
            return "<18";
        }
        if (age <= 24) {
            return "18-24";
        }
        if (age <= 34) {
            return "25-34";
        }
        if (age <= 44) {
            return "35-44";
        }
        return "45+";
    }

    static String activityLevel(long activeDays) {
        if (activeDays >= 15) {
            return "高频";
        }
        if (activeDays >= 7) {
            return "中频";
        }
        return activeDays >= 1 ? "低频" : "不活跃";
    }

    static String activeHour(Map<Integer, Long> hours) {
        if (hours == null || hours.isEmpty()) {
            return "未知";
        }
        int peak = 0;
        long max = -1;
        for (Map.Entry<Integer, Long> entry : hours.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > max) {
                max = entry.getValue();
                peak = entry.getKey();
            }
        }
        if (peak >= 22 || peak <= 5) {
            return "深夜";
        }
        if (peak <= 9) {
            return "早间";
        }
        if (peak <= 13) {
            return "午间";
        }
        if (peak <= 17) {
            return "下午";
        }
        return "晚间";
    }

    static String useDepth(long playCnt) {
        if (playCnt >= 100) {
            return "重度";
        }
        if (playCnt >= 30) {
            return "中度";
        }
        return playCnt >= 1 ? "轻度" : "未使用";
    }

    static String socialLevel(long interactCnt) {
        if (interactCnt >= 30) {
            return "高";
        }
        if (interactCnt >= 5) {
            return "中";
        }
        return interactCnt >= 1 ? "低" : "无";
    }

    static String followLevel(Integer followCount) {
        int count = followCount == null ? 0 : followCount;
        if (count >= 50) {
            return "高";
        }
        if (count >= 10) {
            return "中";
        }
        return count >= 1 ? "低" : "无";
    }

    static String lifecycle(int registerDays, long activeDays30, LocalDate lastActive, LocalDate date) {
        if (registerDays <= 7) {
            return "新用户";
        }
        if (lastActive == null) {
            return "已流失";
        }
        long idleDays = ChronoUnit.DAYS.between(lastActive, date);
        if (idleDays > 60) {
            return "已流失";
        }
        if (idleDays > 30) {
            return "流失预警";
        }
        if (idleDays > 7) {
            return "沉默";
        }
        if (registerDays <= 30) {
            return "成长";
        }
        return activeDays30 >= 3 ? "成熟" : "沉默";
    }

    static String rfm(LocalDate lastActive, LocalDate date, long activeDays, long playCnt) {
        int r;
        if (lastActive == null) {
            r = 0;
        } else {
            long idle = ChronoUnit.DAYS.between(lastActive, date);
            r = idle <= 7 ? 2 : (idle <= 30 ? 1 : 0);
        }
        int f = activeDays >= 15 ? 2 : (activeDays >= 7 ? 1 : 0);
        int m = playCnt >= 100 ? 2 : (playCnt >= 30 ? 1 : 0);
        int score = r + f + m;
        if (score >= 5) {
            return "高价值";
        }
        if (score >= 3) {
            return "潜力";
        }
        return score >= 1 ? "一般" : "流失风险";
    }

    static String interest(Map<String, Map<String, Long>> history, Map<Integer, Long> hours) {
        List<String> interests = new ArrayList<>(2);
        String genre = top(history, "genre", null);
        if (genre != null) {
            interests.add(genre + "爱好者");
        }
        String hour = activeHour(hours);
        if ("深夜".equals(hour)) {
            interests.add("深夜听歌族");
        } else if ("早间".equals(hour)) {
            interests.add("通勤听歌族");
        }
        return interests.isEmpty() ? "暂无" : String.join("、", interests);
    }

    private static String top(Map<String, Map<String, Long>> history, String dimension, String fallback) {
        if (history == null) {
            return fallback;
        }
        Map<String, Long> counts = history.get(dimension);
        if (counts == null || counts.isEmpty()) {
            return fallback;
        }
        String best = null;
        long max = -1;
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (blank(entry.getKey())) {
                continue;
            }
            if (entry.getValue() != null && entry.getValue() > max) {
                max = entry.getValue();
                best = entry.getKey();
            }
        }
        return best == null ? fallback : best;
    }

    private Map<Long, Map<String, Map<String, Long>>> loadHistory(LocalDate date) {
        Map<Long, Map<String, Map<String, Long>>> history = new HashMap<>();
        LocalDateTime from = date.minusDays(89).atStartOfDay();
        for (HistoryAggRow row : profileMapper.profileHistory(from, 500000)) {
            if (row.getUserId() == null) {
                continue;
            }
            Map<String, Map<String, Long>> byDimension = history.computeIfAbsent(row.getUserId(),
                    k -> new HashMap<>());
            merge(byDimension, "genre", row.getGenre(), row.getCnt());
            merge(byDimension, "artist", row.getArtistName(), row.getCnt());
            merge(byDimension, "language", row.getLanguage(), row.getCnt());
        }
        return history;
    }

    private static void merge(Map<String, Map<String, Long>> byDimension, String dimension,
                              String key, Long cnt) {
        if (blank(key)) {
            return;
        }
        byDimension.computeIfAbsent(dimension, k -> new HashMap<>())
                .merge(key, value(cnt), Long::sum);
    }

    private static void add(List<UserProfileTag> tags, long userId, String type, String name,
                            String value, String version, LocalDate date) {
        if (value == null || value.isEmpty()) {
            return;
        }
        UserProfileTag tag = new UserProfileTag();
        tag.setId(IdWorker.getId());
        tag.setUserId(userId);
        tag.setTagType(type);
        tag.setTagName(name);
        tag.setTagValue(value.length() > 500 ? value.substring(0, 500) : value);
        tag.setTagVersion(version);
        tag.setUpdateDate(date);
        tags.add(tag);
    }

    private String tagVersion(LocalDate date) {
        return properties.getProfileTagVersion() + date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static LocalDate parseDate(String text, LocalDate fallback) {
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "日期格式应为 yyyy-MM-dd");
        }
    }

    // ================================================================== 目录

    private static List<Map<String, String>> buildCatalog() {
        List<Map<String, String>> catalog = new ArrayList<>();
        catalog.add(item("基础属性", "gender", "性别"));
        catalog.add(item("基础属性", "age_range", "年龄段"));
        catalog.add(item("基础属性", "region", "地区"));
        catalog.add(item("基础属性", "register_days", "注册天数"));
        catalog.add(item("基础属性", "vip_level", "会员等级"));
        catalog.add(item("设备属性", "platform", "设备类型"));
        catalog.add(item("设备属性", "os", "操作系统"));
        catalog.add(item("设备属性", "network", "网络"));
        catalog.add(item("行为属性", "activity_level", "活跃度"));
        catalog.add(item("行为属性", "active_hour", "活跃时段"));
        catalog.add(item("行为属性", "avg_play_minutes", "日均播放分钟"));
        catalog.add(item("音乐偏好", "top_genre", "Top 风格"));
        catalog.add(item("音乐偏好", "top_artist", "Top 歌手"));
        catalog.add(item("音乐偏好", "top_language", "Top 语种"));
        catalog.add(item("消费能力", "use_depth", "使用深度"));
        catalog.add(item("社交属性", "social_level", "社交活跃度"));
        catalog.add(item("社交属性", "follow_level", "关注数分档"));
        catalog.add(item("生命周期", "lifecycle", "生命周期"));
        catalog.add(item("价值分层", "rfm_segment", "RFM 分层"));
        catalog.add(item("兴趣标签", "interest", "兴趣"));
        catalog.add(item("渠道来源", "register_channel", "注册渠道"));
        return Collections.unmodifiableList(catalog);
    }

    private static Map<String, String> item(String type, String name, String desc) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("tagType", type);
        item.put("tagName", name);
        item.put("label", desc);
        return item;
    }
}
