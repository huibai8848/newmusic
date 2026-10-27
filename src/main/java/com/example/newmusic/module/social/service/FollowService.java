package com.example.newmusic.module.social.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.ArtistMapper;
import com.example.newmusic.module.social.config.SocialProperties;
import com.example.newmusic.module.social.entity.ArtistFollow;
import com.example.newmusic.module.social.entity.Follow;
import com.example.newmusic.module.social.entity.PrivacySetting;
import com.example.newmusic.module.social.mapper.ArtistFollowMapper;
import com.example.newmusic.module.social.mapper.FollowMapper;
import com.example.newmusic.module.social.mapper.SocialCounterMapper;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.social.vo.ArtistFollowStatusVO;
import com.example.newmusic.module.social.vo.FollowStatusVO;
import com.example.newmusic.module.socialupgrade.service.IntimacyService;
import com.example.newmusic.module.social.vo.UserBriefVO;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 用户关注与歌手关注（模块 10 FR-19-008/009）。
 */
@Slf4j
@Service
public class FollowService {

    /** 单次发新歌最多推送的粉丝数；PRD 上限 100k，这里取一个对同步循环更安全的量 */
    private static final int MAX_NEW_SONG_PUSH = 5000;
    private static final int DAILY_NOTIFY_LIMIT = 50;

    private final FollowMapper followMapper;
    private final ArtistFollowMapper artistFollowMapper;
    private final ArtistMapper artistMapper;
    private final SocialCounterMapper counters;
    private final SocialUserSupport users;
    private final SocialNotifier notifier;
    private final NotificationService notificationService;
    private final NotificationSettingService settingService;
    private final PrivacySettingService privacySettingService;
    private final RedisOps redisOps;
    private final SocialProperties properties;
    private final IntimacyService intimacyService;

    public FollowService(FollowMapper followMapper, ArtistFollowMapper artistFollowMapper,
                         ArtistMapper artistMapper, SocialCounterMapper counters,
                         SocialUserSupport users, SocialNotifier notifier,
                         NotificationService notificationService,
                         NotificationSettingService settingService,
                         PrivacySettingService privacySettingService,
                         RedisOps redisOps, SocialProperties properties,
                         IntimacyService intimacyService) {
        this.followMapper = followMapper;
        this.artistFollowMapper = artistFollowMapper;
        this.artistMapper = artistMapper;
        this.counters = counters;
        this.users = users;
        this.notifier = notifier;
        this.notificationService = notificationService;
        this.settingService = settingService;
        this.privacySettingService = privacySettingService;
        this.redisOps = redisOps;
        this.properties = properties;
        this.intimacyService = intimacyService;
    }

    @Transactional
    public FollowStatusVO follow(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            throw new BizException(ResultCode.SELF_INTERACTION_FORBIDDEN, "不能关注自己");
        }
        User target = users.requireUser(targetUserId);
        if (users.blockedEither(userId, targetUserId)) {
            throw new BizException(ResultCode.USER_BLACKLISTED);
        }
        boolean already = users.isFollowing(userId, targetUserId);
        if (!already
                && followMapper.countFollowing(userId) >= properties.getFollow().getLimit()) {
            throw new BizException(ResultCode.FOLLOW_LIMIT);
        }

        int inserted = followMapper.insertIgnore(IdWorker.getId(), userId, targetUserId,
                LocalDateTime.now());
        if (inserted > 0) {
            redisOps.addToSet(RedisKeys.socialFollow(userId), String.valueOf(targetUserId), null);
            if (followMapper.countFollowers(targetUserId) < properties.getFollow().getFansSetLimit()) {
                redisOps.addToSet(RedisKeys.socialFans(targetUserId), String.valueOf(userId), null);
            }
            notifyFollow(userId, target);
            // BR-25-119：互相关注一次性 +10 基础分，双向各记一次
            if (users.isFollowing(targetUserId, userId)) {
                intimacyService.record(userId, targetUserId, IntimacyService.WEIGHT_MUTUAL);
                intimacyService.record(targetUserId, userId, IntimacyService.WEIGHT_MUTUAL);
            }
        }
        return status(userId, targetUserId);
    }

    @Transactional
    public FollowStatusVO unfollow(Long userId, Long targetUserId) {
        int deleted = followMapper.deleteByPair(userId, targetUserId);
        if (deleted > 0) {
            redisOps.removeFromSet(RedisKeys.socialFollow(userId), String.valueOf(targetUserId));
            redisOps.removeFromSet(RedisKeys.socialFans(targetUserId), String.valueOf(userId));
        }
        return status(userId, targetUserId);
    }

    public FollowStatusVO status(Long viewerId, Long targetUserId) {
        FollowStatusVO vo = new FollowStatusVO();
        vo.setUserId(targetUserId);
        vo.setFollowed(viewerId != null && users.isFollowing(viewerId, targetUserId));
        vo.setMutual(viewerId != null && users.isMutual(viewerId, targetUserId));
        vo.setFollowingCount((long) followMapper.countFollowing(targetUserId));
        vo.setFollowerCount((long) followMapper.countFollowers(targetUserId));
        return vo;
    }

    public PageResult<UserBriefVO> following(Long ownerId, Long viewerId, int pageNum, int pageSize) {
        if (!canView(ownerId, viewerId, true)) {
            return PageResult.empty(pageNum, pageSize);
        }
        Page<Follow> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100));
        Page<Follow> result = followMapper.selectPage(page, new QueryWrapper<Follow>()
                .eq("follower_id", ownerId).orderByDesc("create_time").orderByDesc("id"));
        List<Long> ids = result.getRecords().stream()
                .map(Follow::getFollowingId).collect(Collectors.toList());
        List<UserBriefVO> list = users.briefs(ids, viewerId);
        return PageResult.of(result, item -> find(list, item.getFollowingId()), false);
    }

    public PageResult<UserBriefVO> followers(Long ownerId, Long viewerId, int pageNum, int pageSize) {
        if (!canView(ownerId, viewerId, false)) {
            return PageResult.empty(pageNum, pageSize);
        }
        Page<Follow> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100));
        Page<Follow> result = followMapper.selectPage(page, new QueryWrapper<Follow>()
                .eq("following_id", ownerId).orderByDesc("create_time").orderByDesc("id"));
        List<Long> ids = result.getRecords().stream()
                .map(Follow::getFollowerId).collect(Collectors.toList());
        List<UserBriefVO> list = users.briefs(ids, viewerId);
        return PageResult.of(result, item -> find(list, item.getFollowerId()), false);
    }

    private boolean canView(Long ownerId, Long viewerId, boolean followingList) {
        if (Objects.equals(ownerId, viewerId)) {
            return true;
        }
        if (viewerId != null && users.blockedEither(ownerId, viewerId)) {
            return false;
        }
        PrivacySetting privacy = privacySettingService.get(ownerId);
        return followingList
                ? !Integer.valueOf(0).equals(privacy.getShowFollowing())
                : !Integer.valueOf(0).equals(privacy.getShowFollowers());
    }

    private static UserBriefVO find(List<UserBriefVO> list, Long id) {
        UserBriefVO found = list.stream().filter(v -> Objects.equals(v.getId(), id))
                .findFirst().orElse(null);
        if (found != null) {
            return found;
        }
        UserBriefVO placeholder = new UserBriefVO();
        placeholder.setId(id);
        placeholder.setNickname("用户已注销");
        placeholder.setFollowed(false);
        placeholder.setMutual(false);
        return placeholder;
    }

    private void notifyFollow(Long followerId, User target) {
        // 单日关注超过 50 次后不再逐条通知，避免刷屏（PRD FR-19-008 的聚合策略）
        long daily = redisOps.increment(
                RedisKeys.socialFollowDay(followerId, LocalDate.now().toString()),
                Duration.ofDays(1));
        if (daily > DAILY_NOTIFY_LIMIT) {
            return;
        }
        User follower = users.requireUser(followerId);
        notifier.notify(target.getId(), Notification.TYPE_FOLLOW, "新的关注",
                safeNickname(follower) + " 关注了你", followerId, follower,
                Notification.TARGET_USER, followerId);
    }

    // ------------------------------------------------------------------ artist

    @Transactional
    public ArtistFollowStatusVO followArtist(Long userId, Long artistId) {
        Artist artist = artistMapper.selectById(artistId);
        if (artist == null || Integer.valueOf(0).equals(artist.getStatus())) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "歌手不存在");
        }
        int inserted = artistFollowMapper.insertIgnore(IdWorker.getId(), userId, artistId,
                LocalDateTime.now());
        if (inserted > 0) {
            counters.artistFollower(artistId, 1);
        }
        return artistStatus(userId, artistId);
    }

    @Transactional
    public ArtistFollowStatusVO unfollowArtist(Long userId, Long artistId) {
        int deleted = artistFollowMapper.deleteByPair(userId, artistId);
        if (deleted > 0) {
            counters.artistFollower(artistId, -1);
        }
        return artistStatus(userId, artistId);
    }

    public ArtistFollowStatusVO artistStatus(Long userId, Long artistId) {
        ArtistFollowStatusVO vo = new ArtistFollowStatusVO();
        vo.setArtistId(artistId);
        vo.setFollowed(userId != null && artistFollowMapper.selectCount(
                new QueryWrapper<ArtistFollow>().eq("user_id", userId).eq("artist_id", artistId)) > 0);
        vo.setFollowerCount(artistFollowMapper.countByArtist(artistId));
        return vo;
    }

    /**
     * 歌手发新歌通知（FR-19-009）。由曲库发布流程调用。
     *
     * <p>同步逐条落库 + 推送；单次最多 {@value #MAX_NEW_SONG_PUSH} 人。粉丝量再大时
     * 应改为消息队列分批，这里刻意保留上限避免一次请求打满数据库。
     */
    public void notifyNewSong(Song song) {
        if (song == null || song.getArtistId() == null) {
            return;
        }
        List<ArtistFollow> follows = artistFollowMapper.selectList(
                new QueryWrapper<ArtistFollow>().eq("artist_id", song.getArtistId())
                        .last("LIMIT " + MAX_NEW_SONG_PUSH));
        if (follows.isEmpty()) {
            return;
        }
        Artist artist = artistMapper.selectById(song.getArtistId());
        for (ArtistFollow follow : follows) {
            try {
                if (!settingService.enabled(follow.getUserId(), Notification.TYPE_NEW_SONG)) {
                    continue;
                }
                Notification n = new Notification();
                n.setUserId(follow.getUserId());
                n.setNotifyType(Notification.TYPE_NEW_SONG);
                n.setTitle("关注歌手发新歌");
                n.setContent((artist == null ? "你关注的歌手" : artist.getName())
                        + " 发布了新歌《" + song.getName() + "》");
                if (artist != null) {
                    n.setFromNickname(artist.getName());
                    n.setFromAvatar(artist.getAvatar());
                }
                n.setTargetType(Notification.TARGET_SONG);
                n.setTargetId(song.getId());
                n.setIsRead(0);
                n.setCreateTime(LocalDateTime.now());
                notificationService.createAndPush(n);
            } catch (Exception e) {
                log.warn("新歌通知失败 userId={} songId={} cause={}",
                        follow.getUserId(), song.getId(), e.toString());
            }
        }
    }

    private static String safeNickname(User user) {
        return user.getNickname() == null ? "用户" : user.getNickname();
    }
}
