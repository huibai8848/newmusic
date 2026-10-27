package com.example.newmusic.module.socialupgrade.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.social.mapper.BlacklistMapper;
import com.example.newmusic.module.social.mapper.FollowMapper;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.socialupgrade.entity.UserPost;
import com.example.newmusic.module.socialupgrade.entity.UserProfile;
import com.example.newmusic.module.socialupgrade.mapper.UserPostMapper;
import com.example.newmusic.module.socialupgrade.mapper.UserProfileMapper;
import com.example.newmusic.module.socialupgrade.vo.FeedVO;
import com.example.newmusic.module.user.entity.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 发现页推荐（FR-25-006）。
 *
 * <p>简化排序按 PRD 25 §5 的权重：
 * {@code 0.35 社交 + 0.25 质量 + 0.15 时效 + 0.15 兴趣 + 0.10 作者质量}。
 * 候选池一次最多取 500 条 7 天内的公开动态，在内存中过滤/打分/去重后分页，
 * 避免为每种信号再引入一套离线特征表。
 */
@Service
public class DiscoverService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CANDIDATE_LIMIT = 500;
    private static final int WINDOW_DAYS = 7;
    private static final int MAX_SEEN = 3;
    private static final double QUALITY_GATE = 0.02;
    private static final double QUALITY_FULL = 0.10;
    private static final double FRESHNESS_LAMBDA = 0.1;

    private final UserPostMapper postMapper;
    private final UserProfileMapper profileMapper;
    private final FollowMapper followMapper;
    private final BlacklistMapper blacklistMapper;
    private final SocialUserSupport users;
    private final RedisOps redisOps;
    private final PostAssembler assembler;

    public DiscoverService(UserPostMapper postMapper, UserProfileMapper profileMapper,
                           FollowMapper followMapper, BlacklistMapper blacklistMapper,
                           SocialUserSupport users, RedisOps redisOps, PostAssembler assembler) {
        this.postMapper = postMapper;
        this.profileMapper = profileMapper;
        this.followMapper = followMapper;
        this.blacklistMapper = blacklistMapper;
        this.users = users;
        this.redisOps = redisOps;
        this.assembler = assembler;
    }

    public FeedVO discover(Long userId, String cursor, int limit) {
        int size = Math.min(Math.max(1, limit), 30);
        int offset = parseCursor(cursor);

        List<UserPost> candidates = postMapper.selectDiscoverCandidates(
                LocalDateTime.now().minusDays(WINDOW_DAYS), CANDIDATE_LIMIT);
        FeedVO vo = new FeedVO();
        vo.setNewCount(null);
        if (candidates == null || candidates.isEmpty()) {
            vo.setList(Collections.emptyList());
            vo.setHasMore(false);
            vo.setNextCursor(null);
            return vo;
        }

        Set<Long> following = new HashSet<>(followMapper.selectFollowingAll(userId));
        Set<Long> blocked = blockedIds(userId);
        Set<Long> disliked = parseIds(redisOps.members(RedisKeys.postDislike(userId)));
        Map<String, String> seen = redisOps.hashGetAll(RedisKeys.postSeen(userId));
        Map<Long, Integer> mutual = mutualCounts(userId);
        Set<String> interests = interests(userId);

        Set<Long> authorIds = candidates.stream().map(UserPost::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> authors = users.loadUsers(authorIds);
        Map<Long, UserProfile> profiles = loadProfiles(authorIds);

        boolean newUser = following.isEmpty() && interests.isEmpty();

        List<Scored> eligible = new ArrayList<>();
        List<Scored> fallback = new ArrayList<>();
        for (UserPost post : candidates) {
            if (!visible(userId, post, following, blocked, disliked, seen, authors)) {
                continue;
            }
            double score = score(post, profiles.get(post.getUserId()), mutual, interests, newUser);
            Scored item = new Scored(post, score, heat(post));
            if (newUser || passesQuality(post, profiles.get(post.getUserId()))) {
                eligible.add(item);
            } else {
                fallback.add(item);
            }
        }

        eligible.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
        fallback.sort(Comparator.comparingDouble((Scored s) -> s.heat).reversed());

        // 首屏同一作者只留一条；这里对整批候选去重，翻页也不会再出现同作者
        List<UserPost> ranked = dedupeByAuthor(eligible);
        int need = offset + size;
        if (ranked.size() < need) {
            Set<Long> usedAuthors = ranked.stream().map(p -> p.getUserId())
                    .collect(Collectors.toSet());
            for (UserPost post : dedupeByAuthor(fallback)) {
                if (ranked.size() >= need) {
                    break;
                }
                if (usedAuthors.add(post.getUserId())) {
                    ranked.add(post);
                }
            }
        }

        List<UserPost> page = offset >= ranked.size() ? Collections.emptyList()
                : ranked.subList(offset, Math.min(offset + size, ranked.size()));
        for (UserPost post : page) {
            redisOps.hashIncrement(RedisKeys.postSeen(userId), String.valueOf(post.getId()), 1,
                    Duration.ofDays(WINDOW_DAYS));
        }

        vo.setList(assembler.assemble(page, userId));
        int next = offset + page.size();
        vo.setHasMore(next < ranked.size());
        vo.setNextCursor(vo.getHasMore() ? String.valueOf(next) : null);
        return vo;
    }

    // ------------------------------------------------------------------ score

    private double score(UserPost post, UserProfile profile, Map<Long, Integer> mutual,
                         Set<String> interests, boolean newUser) {
        double quality = quality(post);
        double freshness = Math.exp(-FRESHNESS_LAMBDA * hoursSince(post.getCreateTime()));
        double authorQuality = authorQuality(profile);
        if (newUser) {
            // BR-25-066 冷启动直接按热度，避免没有关注/兴趣时分数全为 0
            return 0.70 * normalizeHeat(heat(post)) + 0.20 * quality + 0.10 * freshness;
        }
        double social = Math.min(1.0, mutual.getOrDefault(post.getUserId(), 0) / 5.0);
        double interest = interestMatch(post, interests);
        return 0.35 * social + 0.25 * quality + 0.15 * freshness
                + 0.15 * interest + 0.10 * authorQuality;
    }

    private boolean passesQuality(UserPost post, UserProfile profile) {
        if (profile != null && profile.getCertType() != null && profile.getCertType() > 0) {
            return true;
        }
        return quality(post) > 0;
    }

    private double quality(UserPost post) {
        long interactions = n(post.getLikeCount()) + n(post.getCommentCount())
                + n(post.getRepostCount()) + n(post.getCollectCount());
        long views = n(post.getViewCount());
        if (interactions <= 0 || views <= 0) {
            return 0;
        }
        double rate = (double) interactions / views;
        if (rate <= QUALITY_GATE) {
            return 0;
        }
        return Math.min(1.0, rate / QUALITY_FULL);
    }

    private double authorQuality(UserProfile profile) {
        if (profile == null) {
            return 0;
        }
        if (profile.getCertType() != null && profile.getCertType() > 0) {
            return 1.0;
        }
        return Math.min(0.5, n(profile.getFansCount()) / 5000.0 * 0.5);
    }

    private double interestMatch(UserPost post, Set<String> interests) {
        if (interests.isEmpty()) {
            return 0;
        }
        Set<String> topics = new HashSet<>(parseList(post.getTopics()));
        if (topics.isEmpty()) {
            return 0;
        }
        Set<String> union = new HashSet<>(interests);
        union.addAll(topics);
        Set<String> intersection = new HashSet<>(topics);
        intersection.retainAll(interests);
        if (union.isEmpty()) {
            return 0;
        }
        return (double) intersection.size() / union.size();
    }

    private static double heat(UserPost post) {
        return n(post.getLikeCount()) * 3.0 + n(post.getCommentCount()) * 2.0
                + n(post.getRepostCount()) * 2.0 + n(post.getCollectCount())
                + n(post.getViewCount()) * 0.01;
    }

    private static double normalizeHeat(double heat) {
        return heat <= 0 ? 0 : heat / (heat + 50.0);
    }

    private static double hoursSince(LocalDateTime time) {
        if (time == null) {
            return WINDOW_DAYS * 24.0;
        }
        long minutes = Math.max(0, ChronoUnit.MINUTES.between(time, LocalDateTime.now()));
        return minutes / 60.0;
    }

    // ------------------------------------------------------------------ filters

    private boolean visible(Long viewerId, UserPost post, Set<Long> following, Set<Long> blocked,
                            Set<Long> disliked, Map<String, String> seen, Map<Long, User> authors) {
        if (!Integer.valueOf(UserPost.STATUS_PUBLISHED).equals(post.getStatus())
                || !Integer.valueOf(UserPost.VISIBILITY_PUBLIC).equals(post.getVisibility())) {
            return false;
        }
        Long authorId = post.getUserId();
        if (authorId == null || authorId.equals(viewerId) || blocked.contains(authorId)
                || following.contains(authorId) || disliked.contains(post.getId())) {
            return false;
        }
        String exposed = seen.get(String.valueOf(post.getId()));
        if (exposed != null) {
            try {
                if (Integer.parseInt(exposed) >= MAX_SEEN) {
                    return false;
                }
            } catch (NumberFormatException ignored) {
                // 历史值损坏时按未看过处理
            }
        }
        User author = authors.get(authorId);
        return author != null && !Integer.valueOf(2).equals(author.getStatus())
                && !Integer.valueOf(4).equals(author.getStatus());
    }

    private Set<Long> blockedIds(Long userId) {
        Set<Long> ids = new HashSet<>(blacklistMapper.selectBlockedIds(userId));
        ids.addAll(blacklistMapper.selectBlockerIds(userId));
        ids.remove(userId);
        return ids;
    }

    private Map<Long, Integer> mutualCounts(Long userId) {
        Map<Long, Integer> map = new HashMap<>();
        for (FollowMapper.MutualRow row : followMapper.selectMutualCounts(userId, 200)) {
            if (row.getUserId() != null) {
                map.put(row.getUserId(), row.getCnt() == null ? 0 : row.getCnt().intValue());
            }
        }
        return map;
    }

    /** 用户最近 20 条动态的话题集合，作为轻量兴趣标签。 */
    private Set<String> interests(Long userId) {
        Set<String> topics = new LinkedHashSet<>();
        for (UserPost post : postMapper.selectRecentSelf(userId, 20)) {
            topics.addAll(parseList(post.getTopics()));
        }
        return topics;
    }

    private Map<Long, UserProfile> loadProfiles(Set<Long> ids) {
        Map<Long, UserProfile> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (UserProfile profile : profileMapper.selectBatchIds(ids)) {
            map.put(profile.getUserId(), profile);
        }
        return map;
    }

    private static List<UserPost> dedupeByAuthor(List<Scored> scored) {
        Set<Long> seen = new HashSet<>();
        List<UserPost> out = new ArrayList<>();
        for (Scored item : scored) {
            if (seen.add(item.post.getUserId())) {
                out.add(item.post);
            }
        }
        return out;
    }

    private static Set<Long> parseIds(Set<String> raw) {
        Set<Long> ids = new HashSet<>();
        if (raw == null) {
            return ids;
        }
        for (String value : raw) {
            try {
                ids.add(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                // 忽略脏数据
            }
        }
        return ids;
    }

    private static List<String> parseList(String json) {
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

    private static int parseCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long n(Integer value) {
        return value == null ? 0 : value;
    }

    private static final class Scored {
        private final UserPost post;
        private final double score;
        private final double heat;

        private Scored(UserPost post, double score, double heat) {
            this.post = post;
            this.score = score;
            this.heat = heat;
        }
    }
}
