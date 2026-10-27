package com.example.newmusic.module.socialupgrade.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.social.mapper.BlacklistMapper;
import com.example.newmusic.module.social.mapper.FollowMapper;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.social.vo.UserBriefVO;
import com.example.newmusic.module.socialupgrade.entity.UserProfile;
import com.example.newmusic.module.socialupgrade.mapper.SocialUserQueryMapper;
import com.example.newmusic.module.socialupgrade.mapper.UserProfileMapper;
import com.example.newmusic.module.socialupgrade.vo.RecommendUserVO;
import com.example.newmusic.module.user.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 关系链推荐（FR-25-009）。
 *
 * <p>在线实时拼装，优先级：关注了我但未回关 → 二度关系 → 同城 → 热门。
 * Redis 只保存“忽略”集合（30 天），因此 Redis 不可用时推荐仍然可用。
 */
@Service
public class UserRecommendService {

    private static final int FALLBACK_SCAN = 60;
    private static final int IGNORE_DAYS = 30;

    private final FollowMapper followMapper;
    private final BlacklistMapper blacklistMapper;
    private final SocialUserQueryMapper userQuery;
    private final UserProfileMapper profileMapper;
    private final SocialUserSupport users;
    private final RedisOps redisOps;

    public UserRecommendService(FollowMapper followMapper, BlacklistMapper blacklistMapper,
                                SocialUserQueryMapper userQuery, UserProfileMapper profileMapper,
                                SocialUserSupport users, RedisOps redisOps) {
        this.followMapper = followMapper;
        this.blacklistMapper = blacklistMapper;
        this.userQuery = userQuery;
        this.profileMapper = profileMapper;
        this.users = users;
        this.redisOps = redisOps;
    }

    public List<RecommendUserVO> recommend(Long userId, int limit) {
        int size = Math.min(Math.max(1, limit), 20);
        Set<Long> following = new HashSet<>(followMapper.selectFollowingAll(userId));
        Set<Long> blocked = blockedIds(userId);
        Set<Long> ignored = parseIds(redisOps.members(RedisKeys.relationRecommendIgnore(userId)));

        LinkedHashSet<Long> ordered = new LinkedHashSet<>();
        Map<Long, String> reasons = new HashMap<>();
        Map<Long, String> sources = new HashMap<>();
        Map<Long, Integer> mutualCounts = new HashMap<>();
        Set<Long> followBack = new HashSet<>();

        // 1. 关注了我、我还没回关：回关率最高
        for (Long id : safe(followMapper.selectFollowersNotFollowingBack(userId, FALLBACK_SCAN))) {
            if (eligible(id, userId, following, blocked, ignored)) {
                ordered.add(id);
                reasons.put(id, "关注了你");
                sources.put(id, "mutual");
                followBack.add(id);
            }
        }

        // 2. 二度关系：我关注的人也关注了 TA
        for (FollowMapper.MutualRow row : safe(followMapper.selectMutualCounts(userId, FALLBACK_SCAN))) {
            Long id = row.getUserId();
            if (id == null || !eligible(id, userId, following, blocked, ignored)) {
                continue;
            }
            int count = row.getCnt() == null ? 0 : row.getCnt().intValue();
            ordered.add(id);
            reasons.putIfAbsent(id, "你们有 " + count + " 个共同关注");
            sources.putIfAbsent(id, "second_degree");
            mutualCounts.putIfAbsent(id, count);
        }

        // 3. 同城
        UserProfile profile = profileMapper.selectById(userId);
        if (profile != null && StringUtils.hasText(profile.getRegion())) {
            for (SocialUserQueryMapper.UserSearchRow row
                    : safe(userQuery.sameRegion(userId, profile.getRegion(), FALLBACK_SCAN))) {
                if (eligible(row.getId(), userId, following, blocked, ignored)) {
                    ordered.add(row.getId());
                    reasons.putIfAbsent(row.getId(), "同城用户");
                    sources.putIfAbsent(row.getId(), "region");
                }
            }
        }

        // 4. 热门兜底
        for (SocialUserQueryMapper.UserSearchRow row : safe(userQuery.hotUsers(userId, FALLBACK_SCAN))) {
            if (eligible(row.getId(), userId, following, blocked, ignored)) {
                ordered.add(row.getId());
                reasons.putIfAbsent(row.getId(), "热门用户");
                sources.putIfAbsent(row.getId(), "hot");
            }
        }

        if (ordered.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = new ArrayList<>(ordered).stream().limit(size)
                .collect(java.util.stream.Collectors.toList());
        Map<Long, User> loaded = users.loadUsers(ids);
        List<UserBriefVO> briefs = users.briefs(ids, userId);
        Map<Long, UserBriefVO> briefMap = new HashMap<>();
        for (UserBriefVO brief : briefs) {
            briefMap.put(brief.getId(), brief);
        }

        List<RecommendUserVO> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            User user = loaded.get(id);
            UserBriefVO brief = briefMap.get(id);
            if (user == null || brief == null || Integer.valueOf(4).equals(user.getStatus())) {
                continue;
            }
            RecommendUserVO vo = new RecommendUserVO();
            vo.setUser(brief);
            vo.setFollowBack(followBack.contains(id));
            vo.setReason(reasons.getOrDefault(id, "推荐关注"));
            vo.setSource(sources.getOrDefault(id, "hot"));
            vo.setMutualCount(mutualCounts.get(id));
            out.add(vo);
        }
        return out;
    }

    /** 忽略推荐：30 天内不再出现。 */
    public void ignore(Long userId, Long targetUserId) {
        if (targetUserId == null || userId == null || userId.equals(targetUserId)) {
            return;
        }
        redisOps.addToSet(RedisKeys.relationRecommendIgnore(userId), String.valueOf(targetUserId),
                Duration.ofDays(IGNORE_DAYS));
    }

    private boolean eligible(Long id, Long viewerId, Set<Long> following, Set<Long> blocked,
                             Set<Long> ignored) {
        return id != null && !id.equals(viewerId) && !following.contains(id)
                && !blocked.contains(id) && !ignored.contains(id);
    }

    private Set<Long> blockedIds(Long userId) {
        Set<Long> ids = new HashSet<>(blacklistMapper.selectBlockedIds(userId));
        ids.addAll(blacklistMapper.selectBlockerIds(userId));
        ids.remove(userId);
        return ids;
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

    private static <T> List<T> safe(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }
}
