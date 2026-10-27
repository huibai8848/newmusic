package com.example.newmusic.module.socialupgrade.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.music.entity.Playlist;
import com.example.newmusic.module.music.mapper.PlaylistMapper;
import com.example.newmusic.module.play.entity.PlayHistory;
import com.example.newmusic.module.play.mapper.PlayHistoryMapper;
import com.example.newmusic.module.search.support.SensitiveWordSupport;
import com.example.newmusic.module.social.entity.PrivacySetting;
import com.example.newmusic.module.social.mapper.FollowMapper;
import com.example.newmusic.module.social.service.PrivacySettingService;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.social.vo.UserBriefVO;
import com.example.newmusic.module.socialupgrade.dto.ProfileUpdateReq;
import com.example.newmusic.module.socialupgrade.entity.UserProfile;
import com.example.newmusic.module.socialupgrade.entity.UserProfileLog;
import com.example.newmusic.module.socialupgrade.mapper.ProfileStatsMapper;
import com.example.newmusic.module.socialupgrade.mapper.UserProfileLogMapper;
import com.example.newmusic.module.socialupgrade.mapper.UserProfileMapper;
import com.example.newmusic.module.socialupgrade.vo.ProfileStatsVO;
import com.example.newmusic.module.socialupgrade.vo.ProfileVO;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.mapper.UserMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 个人主页与资料编辑（FR-25-001 / FR-25-002）。
 */
@Slf4j
@Service
public class ProfileService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NICKNAME = Pattern.compile("^[\\u4e00-\\u9fa5A-Za-z0-9_]{2,20}$");
    private static final Pattern USER_CODE = Pattern.compile("^[A-Za-z0-9]{4,20}$");
    private static final List<String> IMPERSONATION = Arrays.asList(
            "官方", "客服", "管理员", "系统", "运营");
    private static final Set<String> PRIVACY_KEYS = new HashSet<>(Arrays.asList(
            "playDuration", "weekCount", "topArtist", "genre", "listenDays",
            "recentListening", "fansRank", "profile", "intimacyScore"));
    private static final int AVATAR_DAILY_LIMIT = 5;
    private static final int BIO_DAILY_LIMIT = 3;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final UserMapper userMapper;
    private final UserProfileMapper profileMapper;
    private final UserProfileLogMapper profileLogMapper;
    private final FollowMapper followMapper;
    private final SocialUserSupport users;
    private final SensitiveWordSupport sensitiveWords;
    private final RedisOps redisOps;
    private final ProfileStatsMapper statsMapper;
    private final PlayHistoryMapper playHistoryMapper;
    private final PlaylistMapper playlistMapper;
    private final PrivacySettingService privacySettingService;
    private final MediaStorageService media;
    private final com.example.newmusic.module.socialupgrade.mapper.UserPostMapper postMapper;

    public ProfileService(UserMapper userMapper, UserProfileMapper profileMapper,
                          UserProfileLogMapper profileLogMapper, FollowMapper followMapper,
                          SocialUserSupport users, SensitiveWordSupport sensitiveWords,
                          RedisOps redisOps, ProfileStatsMapper statsMapper,
                          PlayHistoryMapper playHistoryMapper, PlaylistMapper playlistMapper,
                          PrivacySettingService privacySettingService, MediaStorageService media,
                          com.example.newmusic.module.socialupgrade.mapper.UserPostMapper postMapper) {
        this.userMapper = userMapper;
        this.profileMapper = profileMapper;
        this.profileLogMapper = profileLogMapper;
        this.followMapper = followMapper;
        this.users = users;
        this.sensitiveWords = sensitiveWords;
        this.redisOps = redisOps;
        this.statsMapper = statsMapper;
        this.playHistoryMapper = playHistoryMapper;
        this.playlistMapper = playlistMapper;
        this.privacySettingService = privacySettingService;
        this.media = media;
        this.postMapper = postMapper;
    }

    // ------------------------------------------------------------------ profile

    public ProfileVO getProfile(Long targetUserId, Long viewerId, String ip) {
        User target = users.requireUser(targetUserId);
        UserProfile profile = requireProfile(targetUserId);
        boolean self = Objects.equals(targetUserId, viewerId);
        boolean banned = Integer.valueOf(2).equals(target.getStatus());
        boolean blocked = viewerId != null && users.blockedEither(viewerId, targetUserId);
        boolean followed = viewerId != null && !self && users.isFollowing(viewerId, targetUserId);

        boolean visible = !banned && !blocked && contentVisible(profile, viewerId, self, followed);
        String hint = null;
        if (banned) {
            hint = "该用户已被封禁";
        } else if (blocked) {
            hint = "对方已设置隐私";
        } else if (!visible && !self) {
            hint = Integer.valueOf(2).equals(profile.getProfileVisibility())
                    ? "该用户设置了仅自己可见" : "关注后可查看";
        }

        if (!self && ip != null) {
            redisOps.increment(RedisKeys.profileView(targetUserId, LocalDate.now().format(DAY)),
                    Duration.ofDays(2));
        }

        ProfileVO vo = new ProfileVO();
        vo.setUserId(target.getId());
        vo.setUserCode(profile.getUserCode());
        vo.setNickname(target.getNickname());
        vo.setAvatar(target.getAvatar());
        vo.setCoverUrl(profile.getCoverUrl());
        vo.setBio(profile.getBio() != null ? profile.getBio() : target.getSignature());
        vo.setGender(profile.getGender());
        // BR-25-019：生日不公开具体日期，只给年龄段
        vo.setBirthdayText(birthdayText(profile.getBirthday()));
        vo.setRegion(profile.getRegion());
        vo.setTags(parseTags(profile.getTags()));
        vo.setCertType(profile.getCertType());
        vo.setCertInfo(profile.getCertInfo());
        vo.setJoinTime(target.getCreateTime());
        vo.setFollowCount(followMapper.countFollowing(targetUserId));
        vo.setFansCount(followMapper.countFollowers(targetUserId));
        vo.setPostCount(profile.getPostCount());
        vo.setLikeReceivedCount(profile.getLikeReceivedCount());
        vo.setFollowed(followed);
        vo.setMutual(followed && users.isFollowing(targetUserId, viewerId));
        vo.setSelf(self);
        vo.setBlocked(blocked);
        vo.setBanned(banned);
        vo.setContentVisible(visible);
        vo.setVisibilityHint(hint);
        vo.setPrivacy(privacyMap(profile, self));
        return vo;
    }

    public ProfileVO update(Long userId, ProfileUpdateReq req, String ip) {
        User user = users.requireUser(userId);
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new BizException(ResultCode.PROFILE_FORBIDDEN);
        }
        UserProfile profile = requireProfile(userId);
        Map<String, Object> privacy = parseJson(profile.getPrivacyJson());
        boolean changed = false;

        if (req.getNickname() != null && !req.getNickname().equals(user.getNickname())) {
            updateNickname(user, profile, req.getNickname(), ip);
            changed = true;
        }
        if (req.getBio() != null && !req.getBio().equals(profile.getBio())) {
            updateBio(profile, req.getBio(), ip);
            changed = true;
        }
        if (req.getGender() != null) {
            checkRange(req.getGender(), 0, 2, "性别不合法");
            if (!Objects.equals(req.getGender(), profile.getGender())) {
                log(profile.getUserId(), "gender", profile.getGender(), req.getGender(), ip);
                profile.setGender(req.getGender());
                user.setGender(req.getGender());
                changed = true;
            }
        }
        if (req.getBirthday() != null && !req.getBirthday().equals(profile.getBirthday())) {
            if (req.getBirthday().isAfter(LocalDate.now())) {
                throw new BizException(ResultCode.PARAM_INVALID, "生日不能晚于今天");
            }
            log(profile.getUserId(), "birthday", profile.getBirthday(), req.getBirthday(), ip);
            profile.setBirthday(req.getBirthday());
            user.setBirthday(req.getBirthday());
            changed = true;
        }
        if (req.getRegion() != null && !req.getRegion().equals(profile.getRegion())) {
            if (sensitiveWords.isRisky(req.getRegion())) {
                throw new BizException(ResultCode.CONTENT_ILLEGAL);
            }
            log(profile.getUserId(), "region", profile.getRegion(), req.getRegion(), ip);
            profile.setRegion(req.getRegion());
            changed = true;
        }
        if (req.getTags() != null) {
            if (req.getTags().size() > 5) {
                throw new BizException(ResultCode.PARAM_INVALID, "个人标签最多 5 个");
            }
            for (String tag : req.getTags()) {
                if (!StringUtils.hasText(tag) || tag.length() > 16) {
                    throw new BizException(ResultCode.PARAM_INVALID, "标签长度需在 1-16 字之间");
                }
                if (sensitiveWords.isRisky(tag)) {
                    throw new BizException(ResultCode.CONTENT_ILLEGAL);
                }
            }
            String json = toJson(req.getTags());
            if (!json.equals(profile.getTags())) {
                log(profile.getUserId(), "tags", profile.getTags(), json, ip);
                profile.setTags(json);
                changed = true;
            }
        }
        if (req.getUserCode() != null && !req.getUserCode().equals(profile.getUserCode())) {
            updateUserCode(profile, req.getUserCode(), ip);
            changed = true;
        }
        if (req.getProfileVisibility() != null) {
            checkRange(req.getProfileVisibility(), 0, 2, "可见范围不合法");
            if (!Objects.equals(req.getProfileVisibility(), profile.getProfileVisibility())) {
                log(profile.getUserId(), "profileVisibility", profile.getProfileVisibility(),
                        req.getProfileVisibility(), ip);
                profile.setProfileVisibility(req.getProfileVisibility());
                privacy.put("profile", req.getProfileVisibility());
                changed = true;
            }
        }

        if (changed) {
            profile.setPrivacyJson(toJson(privacy));
            profile.setUpdateTime(LocalDateTime.now());
            profileMapper.updateById(profile);
            user.setSignature(profile.getBio());
            userMapper.updateById(user);
        }
        return getProfile(userId, userId, null);
    }

    private void updateNickname(User user, UserProfile profile, String nickname, String ip) {
        if (!NICKNAME.matcher(nickname).matches()) {
            throw new BizException(ResultCode.PARAM_INVALID, "昵称需为 2-20 位中英文、数字或下划线");
        }
        if (sensitiveWords.isRisky(nickname)) {
            throw new BizException(ResultCode.CONTENT_ILLEGAL, "昵称包含违规信息");
        }
        if (profile.getCertType() == null || profile.getCertType() == 0) {
            for (String word : IMPERSONATION) {
                if (nickname.contains(word)) {
                    throw new BizException(ResultCode.CONTENT_ILLEGAL, "昵称不得包含「" + word + "」等冒充词汇");
                }
            }
        }
        LocalDateTime last = profile.getNicknameUpdateTime();
        if (last != null) {
            LocalDateTime next = last.plusDays(30);
            if (next.isAfter(LocalDateTime.now())) {
                long days = Math.max(1, ChronoUnit.DAYS.between(LocalDateTime.now(), next) + 1);
                throw new BizException(ResultCode.PROFILE_UPDATE_TOO_FREQUENT,
                        "昵称 30 天内只能修改一次，还需 " + days + " 天");
            }
        }
        log(profile.getUserId(), "nickname", user.getNickname(), nickname, ip);
        user.setNickname(nickname);
        profile.setNicknameUpdateTime(LocalDateTime.now());
    }

    private void updateBio(UserProfile profile, String bio, String ip) {
        if (bio.length() > 100) {
            throw new BizException(ResultCode.PARAM_INVALID, "简介最多 100 字");
        }
        if (sensitiveWords.isRisky(bio)) {
            throw new BizException(ResultCode.CONTENT_ILLEGAL);
        }
        String key = RedisKeys.profileEditRate(profile.getUserId(), "bio", LocalDate.now().format(DAY));
        long used = redisOps.increment(key, Duration.ofDays(1));
        if (used > BIO_DAILY_LIMIT) {
            throw new BizException(ResultCode.PROFILE_UPDATE_TOO_FREQUENT, "简介每天最多修改 3 次");
        }
        log(profile.getUserId(), "bio", profile.getBio(), bio, ip);
        profile.setBio(bio);
        profile.setBioUpdateDate(LocalDate.now());
    }

    private void updateUserCode(UserProfile profile, String code, String ip) {
        if (!USER_CODE.matcher(code).matches()) {
            throw new BizException(ResultCode.PARAM_INVALID, "短码需为 4-20 位字母或数字");
        }
        if (sensitiveWords.isRisky(code)) {
            throw new BizException(ResultCode.CONTENT_ILLEGAL);
        }
        Long owner = profileMapper.selectUserIdByCode(code);
        if (owner != null && !owner.equals(profile.getUserId())) {
            throw new BizException(ResultCode.USER_CODE_DUPLICATED);
        }
        log(profile.getUserId(), "userCode", profile.getUserCode(), code, ip);
        profile.setUserCode(code);
    }

    public String updateAvatar(Long userId, MultipartFile file, String ip) {
        users.requireUser(userId);
        UserProfile profile = requireProfile(userId);
        String key = RedisKeys.profileEditRate(userId, "avatar", LocalDate.now().format(DAY));
        long used = redisOps.increment(key, Duration.ofDays(1));
        if (used > AVATAR_DAILY_LIMIT) {
            throw new BizException(ResultCode.PROFILE_UPDATE_TOO_FREQUENT, "头像每天最多修改 5 次");
        }
        String url = media.saveImage(file, "avatar");
        log(userId, "avatar", null, url, ip);
        User user = users.requireUser(userId);
        user.setAvatar(url);
        userMapper.updateById(user);
        profile.setAvatarUpdateDate(LocalDate.now());
        profile.setUpdateTime(LocalDateTime.now());
        profileMapper.updateById(profile);
        return url;
    }

    public String updateCover(Long userId, MultipartFile file, String ip) {
        users.requireUser(userId);
        UserProfile profile = requireProfile(userId);
        String url = media.saveImage(file, "cover");
        log(userId, "cover", profile.getCoverUrl(), url, ip);
        profile.setCoverUrl(url);
        profile.setUpdateTime(LocalDateTime.now());
        profileMapper.updateById(profile);
        return url;
    }

    // ------------------------------------------------------------------ privacy

    public Map<String, Object> getPrivacy(Long userId) {
        return privacyMap(requireProfile(userId), true);
    }

    public Map<String, Object> updatePrivacy(Long userId, Map<String, Object> req) {
        UserProfile profile = requireProfile(userId);
        Map<String, Object> current = parseJson(profile.getPrivacyJson());
        if (req != null) {
            for (Map.Entry<String, Object> e : req.entrySet()) {
                if (!PRIVACY_KEYS.contains(e.getKey())) {
                    continue;
                }
                int value = asInt(e.getValue(), 0);
                if (value != 0 && value != 1) {
                    throw new BizException(ResultCode.PARAM_INVALID, "隐私设置取值只能是 0 或 1");
                }
                current.put(e.getKey(), value);
                if ("recentListening".equals(e.getKey())) {
                    current.put("recentListening", value);
                }
            }
        }
        if (req != null && req.containsKey("profileVisibility")) {
            int v = asInt(req.get("profileVisibility"), 0);
            checkRange(v, 0, 2, "可见范围不合法");
            profile.setProfileVisibility(v);
        }
        profile.setPrivacyJson(toJson(current));
        profile.setUpdateTime(LocalDateTime.now());
        profileMapper.updateById(profile);
        return current;
    }

    // ------------------------------------------------------------------ stats

    public ProfileStatsVO stats(Long targetUserId, Long viewerId) {
        users.requireUser(targetUserId);
        UserProfile profile = requireProfile(targetUserId);
        if (!canViewContent(profile, targetUserId, viewerId)) {
            ProfileStatsVO hidden = new ProfileStatsVO();
            hidden.setPlayDurationVisible(false);
            hidden.setWeekCountVisible(false);
            hidden.setTopArtistVisible(false);
            hidden.setGenreVisible(false);
            hidden.setListenDaysVisible(false);
            return hidden;
        }
        Map<String, Object> privacy = parseJson(profile.getPrivacyJson());
        boolean self = Objects.equals(targetUserId, viewerId);
        LocalDateTime since = LocalDateTime.now().minusDays(7);

        ProfileStatsVO vo = new ProfileStatsVO();
        vo.setPlayDurationVisible(self || asInt(privacy.get("playDuration"), 0) == 0);
        vo.setWeekCountVisible(self || asInt(privacy.get("weekCount"), 0) == 0);
        vo.setTopArtistVisible(self || asInt(privacy.get("topArtist"), 0) == 0);
        vo.setGenreVisible(self || asInt(privacy.get("genre"), 0) == 0);
        vo.setListenDaysVisible(self || asInt(privacy.get("listenDays"), 0) == 0);

        if (vo.isPlayDurationVisible()) {
            vo.setTotalPlaySeconds(statsMapper.totalPlaySeconds(targetUserId));
        }
        if (vo.isWeekCountVisible()) {
            vo.setWeekSongCount(statsMapper.distinctSongsSince(targetUserId, since));
        }
        if (vo.isListenDaysVisible()) {
            vo.setListenDays(statsMapper.listenDays(targetUserId));
        }
        if (vo.isTopArtistVisible()) {
            vo.setTopArtists(statsMapper.topArtists(targetUserId, since, 3).stream().map(row -> {
                ProfileStatsVO.ArtistItem item = new ProfileStatsVO.ArtistItem();
                item.setArtistId(row.getArtistId());
                item.setArtistName(row.getArtistName());
                item.setPlayCount(row.getPlayCount());
                return item;
            }).collect(Collectors.toList()));
        }
        if (vo.isGenreVisible()) {
            vo.setGenres(statsMapper.genreDistribution(targetUserId, since, 6).stream().map(row -> {
                ProfileStatsVO.GenreItem item = new ProfileStatsVO.GenreItem();
                item.setGenre(row.getGenre());
                item.setPlayCount(row.getPlayCount());
                return item;
            }).collect(Collectors.toList()));
        }
        return vo;
    }

    public List<Map<String, Object>> recentListening(Long targetUserId, Long viewerId) {
        UserProfile profile = requireProfile(targetUserId);
        if (!canViewContent(profile, targetUserId, viewerId)) {
            return Collections.emptyList();
        }
        boolean self = Objects.equals(targetUserId, viewerId);
        Map<String, Object> privacy = parseJson(profile.getPrivacyJson());
        if (!self && asInt(privacy.get("recentListening"), 0) != 1) {
            return Collections.emptyList();
        }
        List<PlayHistory> rows = playHistoryMapper.selectLatestPage(targetUserId, 0, 10);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlayHistory h : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("songId", h.getSongId());
            item.put("name", h.getSongName());
            item.put("artistName", h.getArtistName());
            item.put("playTime", h.getPlayTime());
            out.add(item);
        }
        return out;
    }

    public PageResult<Map<String, Object>> playlists(Long targetUserId, Long viewerId,
                                                     int pageNum, int pageSize) {
        UserProfile profile = requireProfile(targetUserId);
        boolean self = Objects.equals(targetUserId, viewerId);
        boolean followed = viewerId != null && users.isFollowing(viewerId, targetUserId);
        if (!contentVisible(profile, viewerId, self, followed)) {
            return PageResult.empty(pageNum, pageSize);
        }
        Page<Playlist> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 50));
        QueryWrapper<Playlist> wrapper = new QueryWrapper<Playlist>()
                .eq("user_id", targetUserId).eq("status", 1).eq("audit_status", 1);
        if (!self) {
            wrapper.eq("is_public", 1);
        }
        wrapper.orderByDesc("create_time");
        Page<Playlist> result = playlistMapper.selectPage(page, wrapper);
        return PageResult.of(result, p -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", p.getId());
            item.put("name", p.getName());
            item.put("cover", p.getCover());
            item.put("songCount", p.getSongCount());
            item.put("playCount", p.getPlayCount());
            return item;
        }, false);
    }

    public Map<String, Object> about(Long targetUserId, Long viewerId) {
        UserProfile profile = requireProfile(targetUserId);
        if (!canViewContent(profile, targetUserId, viewerId)) {
            return Collections.emptyMap();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userCode", profile.getUserCode());
        map.put("region", profile.getRegion());
        map.put("tags", parseTags(profile.getTags()));
        map.put("certType", profile.getCertType());
        map.put("certInfo", profile.getCertInfo());
        map.put("joinTime", users.requireUser(targetUserId).getCreateTime());
        map.put("bio", profile.getBio());
        return map;
    }

    // ------------------------------------------------------------------ helpers

    public UserProfile requireProfile(Long userId) {
        UserProfile profile = profileMapper.selectByUserId(userId);
        if (profile != null) {
            return profile;
        }
        profileMapper.insertDefault(userId);
        profile = profileMapper.selectByUserId(userId);
        if (profile == null) {
            throw new BizException(ResultCode.SYSTEM_BUSY, "资料初始化失败");
        }
        if (!StringUtils.hasText(profile.getUserCode())) {
            profile.setUserCode(defaultCode(userId));
            profile.setPrivacyJson(toJson(defaultPrivacy()));
            try {
                profileMapper.updateById(profile);
            } catch (DuplicateKeyException e) {
                profile.setUserCode(null);
                profileMapper.updateById(profile);
            }
        }
        profile.setPostCount((int) postCount(userId));
        profile.setLikeReceivedCount(0);
        return profile;
    }

    private long postCount(Long userId) {
        return postMapper.selectCount(new QueryWrapper<com.example.newmusic.module.socialupgrade.entity.UserPost>()
                .eq("user_id", userId).in("status", 0, 1, 3));
    }

    private String defaultCode(Long userId) {
        return "u" + Long.toString(userId, 36);
    }

    private static Map<String, Object> defaultPrivacy() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("playDuration", 0);
        map.put("weekCount", 0);
        map.put("topArtist", 0);
        map.put("genre", 0);
        map.put("listenDays", 0);
        map.put("recentListening", 0);
        map.put("fansRank", 0);
        map.put("profile", 0);
        map.put("intimacyScore", 0);
        return map;
    }

    private Map<String, Object> privacyMap(UserProfile profile, boolean self) {
        Map<String, Object> map = parseJson(profile.getPrivacyJson());
        for (Map.Entry<String, Object> e : defaultPrivacy().entrySet()) {
            map.putIfAbsent(e.getKey(), e.getValue());
        }
        map.put("profile", profile.getProfileVisibility() == null ? 0 : profile.getProfileVisibility());
        // 本人看到的配置始终是自己的真实配置
        return map;
    }

    /** 访客是否有权查看该用户的主页主体内容（动态/歌单/统计/最近常听/关于）。 */
    public boolean canViewContent(Long targetUserId, Long viewerId) {
        return canViewContent(requireProfile(targetUserId), targetUserId, viewerId);
    }

    private boolean canViewContent(UserProfile profile, Long targetUserId, Long viewerId) {
        User target = users.requireUser(targetUserId);
        if (users.isBanned(target)) {
            return false;
        }
        if (viewerId != null && users.blockedEither(viewerId, targetUserId)) {
            return false;
        }
        boolean self = Objects.equals(targetUserId, viewerId);
        boolean followed = viewerId != null && !self && users.isFollowing(viewerId, targetUserId);
        return contentVisible(profile, viewerId, self, followed);
    }

    private boolean contentVisible(UserProfile profile, Long viewerId, boolean self, boolean followed) {
        if (self) {
            return true;
        }
        int visibility = profile.getProfileVisibility() == null ? 0 : profile.getProfileVisibility();
        if (visibility == 2) {
            return false;
        }
        return visibility != 1 || followed;
    }

    private static String birthdayText(LocalDate birthday) {
        if (birthday == null) {
            return null;
        }
        int age = java.time.Period.between(birthday, LocalDate.now()).getYears();
        if (age < 18) {
            return "18岁以下";
        }
        int decade = (age / 10) * 10;
        return decade + "后";
    }

    private void log(Long userId, String field, Object oldValue, Object newValue, String ip) {
        UserProfileLog row = new UserProfileLog();
        row.setUserId(userId);
        row.setField(field);
        row.setOldValue(truncate(oldValue));
        row.setNewValue(truncate(newValue));
        row.setIp(ip);
        row.setCreateTime(LocalDateTime.now());
        profileLogMapper.insert(row);
    }

    private static String truncate(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private static void checkRange(int value, int min, int max, String message) {
        if (value < min || value > max) {
            throw new BizException(ResultCode.PARAM_INVALID, message);
        }
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Map<String, Object> parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>(defaultPrivacy());
        }
        try {
            Map<String, Object> map = JSON.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            return new LinkedHashMap<>(map);
        } catch (Exception e) {
            return new LinkedHashMap<>(defaultPrivacy());
        }
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static List<String> parseTags(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
