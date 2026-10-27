package com.example.newmusic.module.socialupgrade.service;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.social.mapper.BlacklistMapper;
import com.example.newmusic.module.social.mapper.FollowMapper;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.socialupgrade.config.SocialUpgradeProperties;
import com.example.newmusic.module.socialupgrade.entity.UserPost;
import com.example.newmusic.module.socialupgrade.mapper.UserPostMapper;
import com.example.newmusic.module.socialupgrade.vo.FeedVO;
import com.example.newmusic.module.socialupgrade.vo.PostVO;
import com.example.newmusic.module.user.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 关注流（FR-25-005）：发布写入推/拉双通道，读取合并后先过滤再分页。
 *
 * <p>Redis 只是加速层：收件箱缺失、熔断或数据被回收时统一回落到
 * {@link UserPostMapper#selectFollowingPull} 的全拉模式，功能不降级，只降性能。
 */
@Slf4j
@Service
public class FeedService {

    private final FollowMapper followMapper;
    private final UserPostMapper postMapper;
    private final BlacklistMapper blacklistMapper;
    private final SocialUserSupport users;
    private final RedisOps redisOps;
    private final SocialUpgradeProperties properties;
    private final PostAssembler assembler;

    public FeedService(FollowMapper followMapper, UserPostMapper postMapper,
                       BlacklistMapper blacklistMapper, SocialUserSupport users,
                       RedisOps redisOps, SocialUpgradeProperties properties,
                       PostAssembler assembler) {
        this.followMapper = followMapper;
        this.postMapper = postMapper;
        this.blacklistMapper = blacklistMapper;
        this.users = users;
        this.redisOps = redisOps;
        this.properties = properties;
        this.assembler = assembler;
    }

    // ------------------------------------------------------------------ fan-out

    /** 发布后分发。返回写入模式，供排查/验收观察：push / pull。 */
    public String distribute(UserPost post) {
        long score = toMillis(post.getCreateTime());
        Duration ttl = Duration.ofDays(properties.getFeed().getInboxTtlDays());
        String outbox = RedisKeys.feedOutbox(post.getUserId());
        redisOps.zAdd(outbox, String.valueOf(post.getId()), score, ttl);
        redisOps.zRemRangeByRank(outbox, 0,
                -(properties.getFeed().getInboxCapacity() + 1L));

        List<Long> followers = followMapper.selectFollowerIds(post.getUserId());
        if (followers == null || followers.isEmpty()) {
            return "push";
        }
        if (followers.size() >= properties.getFeed().getFanPushThreshold()) {
            // BR-25-047：大 V 不写粉丝收件箱，粉丝读取时从发件箱拉取
            return "pull";
        }
        List<String> inboxes = followers.stream()
                .map(RedisKeys::feedInbox).collect(Collectors.toList());
        redisOps.zAddBatch(inboxes, String.valueOf(post.getId()), score, ttl);
        return "push";
    }

    // ------------------------------------------------------------------ read

    public FeedVO following(Long userId, String cursor, int limit) {
        int size = Math.min(Math.max(1, limit), 50);
        Cursor parsed = Cursor.parse(cursor);
        List<Long> following = followMapper.selectFollowingAll(userId);
        if (following == null || following.isEmpty()) {
            return empty();
        }
        Set<Long> bigVs = bigAccounts(following);
        List<FeedItem> candidates = readRedis(userId, following, bigVs, parsed);
        if (candidates == null) {
            return pullFromDb(userId, following, parsed, size);
        }
        candidates.sort(Comparator.comparingLong((FeedItem i) -> i.score).reversed()
                .thenComparing(Comparator.comparingLong((FeedItem i) -> i.postId).reversed()));

        List<FeedItem> filtered = filter(userId, candidates);
        List<FeedItem> page = filtered.stream().limit(size).collect(Collectors.toList());
        if (page.isEmpty()) {
            // 收件箱可能尚未构建/已过期：直接查 DB 兜底，避免新用户永远空流
            return pullFromDb(userId, following, parsed, size);
        }
        List<UserPost> posts = postMapper.selectBatchIds(page.stream()
                .map(i -> i.postId).collect(Collectors.toList()));
        Map<Long, UserPost> byId = posts.stream()
                .collect(Collectors.toMap(UserPost::getId, p -> p));
        List<UserPost> ordered = page.stream().map(i -> byId.get(i.postId))
                .filter(Objects::nonNull).collect(Collectors.toList());
        List<PostVO> vos = assembler.assemble(ordered, userId);

        FeedItem last = page.get(page.size() - 1);
        FeedVO vo = new FeedVO();
        vo.setList(vos);
        vo.setHasMore(page.size() >= size);
        vo.setNextCursor(vo.getHasMore() ? last.score + "_" + last.postId : null);
        vo.setNewCount(newCount(userId));
        return vo;
    }

    /**
     * 读取 Redis 候选；返回 null 表示 Redis 不可用，调用方走全拉模式。
     */
    private List<FeedItem> readRedis(Long userId, List<Long> following, Set<Long> bigVs, Cursor cursor) {
        if (!redisOps.isAvailable()) {
            return null;
        }
        String inboxKey = RedisKeys.feedInbox(userId);
        if (!redisOps.get(RedisKeys.feedBuilt(userId)).isPresent()) {
            buildInbox(userId, following, bigVs);
        }
        // 懒裁剪，避免每次发布对 5000 个收件箱做 ZREMRANGEBYRANK
        redisOps.zRemRangeByRank(inboxKey, 0, -(properties.getFeed().getInboxCapacity() + 1L));

        int scan = properties.getFeed().getMaxScan();
        Map<Long, FeedItem> merged = new HashMap<>();
        collect(redisOps.zRevRangeWithScores(inboxKey, 0, scan - 1), cursor, merged);
        for (Long bigV : bigVs) {
            collect(redisOps.zRevRangeWithScores(RedisKeys.feedOutbox(bigV), 0, scan - 1),
                    cursor, merged);
        }
        if (!redisOps.isAvailable()) {
            return null;
        }
        return new ArrayList<>(merged.values());
    }

    private void collect(Map<String, Double> range, Cursor cursor, Map<Long, FeedItem> out) {
        if (range == null) {
            return;
        }
        for (Map.Entry<String, Double> e : range.entrySet()) {
            long postId;
            try {
                postId = Long.parseLong(e.getKey());
            } catch (NumberFormatException ex) {
                continue;
            }
            long score = e.getValue() == null ? 0L : e.getValue().longValue();
            if (!cursor.before(score, postId)) {
                continue;
            }
            out.putIfAbsent(postId, new FeedItem(postId, score));
        }
    }

    /** 首次读取时按关注列表回填收件箱（仅小号内容；大 V 走发件箱）。 */
    private void buildInbox(Long userId, List<Long> following, Set<Long> bigVs) {
        List<Long> small = following.stream().filter(id -> !bigVs.contains(id))
                .collect(Collectors.toList());
        if (!small.isEmpty()) {
            List<UserPost> recent = postMapper.selectRecentByAuthors(small,
                    LocalDateTime.now().minusDays(properties.getFeed().getInboxTtlDays()),
                    Math.min(properties.getFeed().getInboxCapacity(), 500));
            List<String> keys = Collections.singletonList(RedisKeys.feedInbox(userId));
            for (UserPost post : recent) {
                redisOps.zAddBatch(keys, String.valueOf(post.getId()),
                        toMillis(post.getCreateTime()),
                        Duration.ofDays(properties.getFeed().getInboxTtlDays()));
            }
        }
        redisOps.set(RedisKeys.feedBuilt(userId), "1",
                Duration.ofDays(properties.getFeed().getInboxTtlDays()));
    }

    private Set<Long> bigAccounts(Collection<Long> following) {
        List<Long> ids = new ArrayList<>(following);
        Map<Long, Long> fans = new HashMap<>();
        for (FollowMapper.FanCountRow row : followMapper.selectFansCounts(ids)) {
            fans.put(row.getUserId(), row.getCnt() == null ? 0L : row.getCnt());
        }
        return ids.stream().filter(id -> fans.getOrDefault(id, 0L)
                >= properties.getFeed().getFanPushThreshold()).collect(Collectors.toSet());
    }

    private List<FeedItem> filter(Long userId, List<FeedItem> candidates) {
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = candidates.stream().map(i -> i.postId).collect(Collectors.toList());
        Map<Long, UserPost> posts = postMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(UserPost::getId, p -> p));
        Set<Long> blocked = blockedIds(userId);
        Map<Long, User> authors = users.loadUsers(posts.values().stream()
                .map(UserPost::getUserId).collect(Collectors.toSet()));
        Set<Long> followedBack = new HashSet<>(followMapper.selectFollowingIds(userId,
                posts.values().stream().map(UserPost::getUserId).distinct().collect(Collectors.toList())));

        List<FeedItem> out = new ArrayList<>();
        for (FeedItem item : candidates) {
            UserPost post = posts.get(item.postId);
            if (post == null || !visible(userId, post, blocked, authors, followedBack)) {
                continue;
            }
            out.add(item);
        }
        return out;
    }

    private boolean visible(Long viewerId, UserPost post, Set<Long> blocked,
                            Map<Long, User> authors, Set<Long> followedBack) {
        if (Objects.equals(viewerId, post.getUserId())) {
            return false;
        }
        if (Integer.valueOf(UserPost.STATUS_DELETED).equals(post.getStatus())
                || Integer.valueOf(UserPost.STATUS_ADMIN_DELETED).equals(post.getStatus())
                || Integer.valueOf(UserPost.STATUS_SELF_ONLY).equals(post.getStatus())) {
            return false;
        }
        if (!Integer.valueOf(UserPost.STATUS_PUBLISHED).equals(post.getStatus())
                || post.getVisibility() == null || post.getVisibility() > 1) {
            // 关注流里 0/1 都可见（viewer 本就关注了作者），2（仅自己）不可见
            return false;
        }
        if (blocked.contains(post.getUserId())) {
            return false;
        }
        User author = authors.get(post.getUserId());
        return author != null && !Integer.valueOf(2).equals(author.getStatus())
                && !Integer.valueOf(4).equals(author.getStatus());
    }

    /** Redis 不可用 / 收件箱未初始化时的全拉降级。 */
    private FeedVO pullFromDb(Long userId, List<Long> following, Cursor cursor, int size) {
        LocalDateTime cursorTime = cursor.time == null
                ? LocalDateTime.now().plusMinutes(1) : cursor.time;
        List<UserPost> posts = postMapper.selectFollowingPull(following, cursorTime,
                cursor.postId == null ? Long.MAX_VALUE : cursor.postId,
                Math.min(properties.getFeed().getMaxScan(), size * 5));
        Set<Long> blocked = blockedIds(userId);
        Map<Long, User> authors = users.loadUsers(posts.stream()
                .map(UserPost::getUserId).collect(Collectors.toSet()));
        Set<Long> followedBack = new HashSet<>(followMapper.selectFollowingIds(userId,
                posts.stream().map(UserPost::getUserId).distinct().collect(Collectors.toList())));
        List<UserPost> filtered = posts.stream()
                .filter(p -> visible(userId, p, blocked, authors, followedBack))
                .filter(p -> !Objects.equals(p.getId(), userId))
                .limit(size).collect(Collectors.toList());

        FeedVO vo = new FeedVO();
        vo.setList(assembler.assemble(filtered, userId));
        vo.setHasMore(filtered.size() >= size);
        if (filtered.isEmpty()) {
            vo.setNextCursor(null);
        } else {
            UserPost last = filtered.get(filtered.size() - 1);
            vo.setNextCursor(toMillis(last.getCreateTime()) + "_" + last.getId());
        }
        vo.setNewCount(newCount(userId));
        return vo;
    }

    public long newCount(Long userId) {
        String lastRead = redisOps.get(RedisKeys.feedLastRead(userId)).orElse(null);
        long since;
        try {
            since = lastRead == null ? 0L : Long.parseLong(lastRead);
        } catch (NumberFormatException e) {
            since = 0L;
        }
        if (since <= 0) {
            return 0;
        }
        return redisOps.zCount(RedisKeys.feedInbox(userId), since + 1, Double.MAX_VALUE);
    }

    public void markRead(Long userId) {
        redisOps.set(RedisKeys.feedLastRead(userId), String.valueOf(System.currentTimeMillis()),
                Duration.ofDays(7));
    }

    private Set<Long> blockedIds(Long viewerId) {
        Set<Long> ids = new HashSet<>(blacklistMapper.selectBlockedIds(viewerId));
        ids.addAll(blacklistMapper.selectBlockerIds(viewerId));
        ids.remove(viewerId);
        return ids;
    }

    private FeedVO empty() {
        FeedVO vo = new FeedVO();
        vo.setList(Collections.emptyList());
        vo.setHasMore(false);
        vo.setNextCursor(null);
        vo.setNewCount(0L);
        return vo;
    }

    static long toMillis(LocalDateTime time) {
        return time == null ? 0L : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static final class FeedItem {
        private final long postId;
        private final long score;

        private FeedItem(long postId, long score) {
            this.postId = postId;
            this.score = score;
        }
    }

    private static final class Cursor {
        private final Long postId;
        private final Long score;
        private final LocalDateTime time;

        private Cursor(Long postId, Long score, LocalDateTime time) {
            this.postId = postId;
            this.score = score;
            this.time = time;
        }

        static Cursor parse(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return new Cursor(null, null, null);
            }
            String[] parts = raw.trim().split("_");
            try {
                long score = Long.parseLong(parts[0]);
                long postId = parts.length > 1 ? Long.parseLong(parts[1]) : Long.MAX_VALUE;
                LocalDateTime time = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(score), ZoneId.systemDefault());
                return new Cursor(postId, score, time);
            } catch (Exception e) {
                return new Cursor(null, null, null);
            }
        }

        boolean before(long score, long postId) {
            if (this.score == null) {
                return true;
            }
            return score < this.score || (score == this.score && postId < this.postId);
        }
    }
}
