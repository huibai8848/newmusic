package com.example.newmusic.module.social.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recommend.dto.FeedbackReq;
import com.example.newmusic.module.recommend.service.FeedbackService;
import com.example.newmusic.module.social.dto.LikeBatchCheckReq;
import com.example.newmusic.module.social.dto.LikeReq;
import com.example.newmusic.module.social.entity.Comment;
import com.example.newmusic.module.social.entity.LikeRecord;
import com.example.newmusic.module.social.mapper.CommentMapper;
import com.example.newmusic.module.social.mapper.LikeMapper;
import com.example.newmusic.module.social.support.SocialTargets;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.social.vo.LikeBatchCheckVO;
import com.example.newmusic.module.social.vo.LikeCountRow;
import com.example.newmusic.module.social.vo.LikeStatusVO;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.ws.entity.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 点赞（模块 10 FR-19-004/005）。
 *
 * <p>MySQL 唯一索引保证幂等；Redis Set 只是「是否点过」的加速镜像。
 */
@Slf4j
@Service
public class LikeService {

    private static final int[] COMMENT_MILESTONES = {10, 50, 100, 500, 1000};

    private final LikeMapper likeMapper;
    private final CommentMapper commentMapper;
    private final SocialTargets targets;
    private final SocialUserSupport users;
    private final SocialNotifier notifier;
    private final RedisOps redisOps;
    private final FeedbackService feedbackService;

    public LikeService(LikeMapper likeMapper, CommentMapper commentMapper, SocialTargets targets,
                       SocialUserSupport users, SocialNotifier notifier, RedisOps redisOps,
                       FeedbackService feedbackService) {
        this.likeMapper = likeMapper;
        this.commentMapper = commentMapper;
        this.targets = targets;
        this.users = users;
        this.notifier = notifier;
        this.redisOps = redisOps;
        this.feedbackService = feedbackService;
    }

    @Transactional
    public LikeStatusVO toggle(Long userId, LikeReq req) {
        String type = targets.requireLikeType(req.getTargetType());
        Long targetId = req.getTargetId();
        targets.require(type, targetId, true);

        boolean wantLike = req.getLike() == null || req.getLike();
        if (wantLike) {
            int inserted = likeMapper.insertIgnore(IdWorker.getId(), userId, type, targetId, LocalDateTime.now());
            if (inserted > 0) {
                onLiked(userId, type, targetId);
            }
        } else {
            int deleted = likeMapper.deleteByUserAndTarget(userId, type, targetId);
            if (deleted > 0) {
                onUnliked(userId, type, targetId);
            }
        }
        return status(userId, type, targetId);
    }

    private void onLiked(Long userId, String type, Long targetId) {
        redisOps.addToSet(RedisKeys.socialLikeTarget(type, targetId), String.valueOf(userId), null);
        redisOps.addToSet(RedisKeys.socialLikeUser(userId, type), String.valueOf(targetId), null);
        targets.addLikeCount(type, targetId, 1);

        if (SocialTargets.COMMENT.equals(type)) {
            commentMapper.updateLikeCount(targetId, 1);
            Comment comment = commentMapper.selectById(targetId);
            if (comment != null) {
                User from = users.requireUser(userId);
                notifier.notify(comment.getUserId(), Notification.TYPE_LIKE, "收到新的点赞",
                        safeNickname(from) + " 赞了你的评论", userId, from,
                        Notification.TARGET_COMMENT, targetId);
                milestone(comment, countOf(type, targetId), from);
            }
        } else if (SocialTargets.SONG.equals(type)) {
            recordRecommendation(userId, targetId, true);
        }
    }

    private void onUnliked(Long userId, String type, Long targetId) {
        redisOps.removeFromSet(RedisKeys.socialLikeTarget(type, targetId), String.valueOf(userId));
        redisOps.removeFromSet(RedisKeys.socialLikeUser(userId, type), String.valueOf(targetId));
        targets.addLikeCount(type, targetId, -1);
        if (SocialTargets.COMMENT.equals(type)) {
            commentMapper.updateLikeCount(targetId, -1);
        } else if (SocialTargets.SONG.equals(type)) {
            recordRecommendation(userId, targetId, false);
        }
    }

    /**
     * 点赞歌曲写入推荐特征（FR-19-005）。
     *
     * <p>推荐模块失败不能回滚社交点赞：用户点的是赞，不是「训练模型」。
     */
    private void recordRecommendation(Long userId, Long songId, boolean like) {
        try {
            FeedbackReq req = new FeedbackReq();
            req.setSongId(songId);
            req.setAction(like ? "LIKE" : "UNDO");
            req.setScene("social_like");
            feedbackService.feedback(userId, req);
        } catch (Exception e) {
            log.warn("点赞写入推荐特征失败（不影响点赞） userId={} songId={} like={} cause={}",
                    userId, songId, like, e.toString());
        }
    }

    private void milestone(Comment comment, long count, User from) {
        for (int milestone : COMMENT_MILESTONES) {
            if (count == milestone
                    && redisOps.setIfAbsent(RedisKeys.socialMilestone(comment.getId(), milestone),
                    "1", Duration.ofDays(90))) {
                notifier.notify(comment.getUserId(), Notification.TYPE_LIKE, "评论点赞里程碑",
                        "你的评论已获得 " + milestone + " 个赞", from.getId(), from,
                        Notification.TARGET_COMMENT, comment.getId());
            }
        }
    }

    public LikeStatusVO status(Long userId, String type, Long targetId) {
        LikeStatusVO vo = new LikeStatusVO();
        vo.setTargetType(type);
        vo.setTargetId(targetId);
        vo.setLiked(liked(userId, type, targetId));
        vo.setCount(countOf(type, targetId));
        return vo;
    }

    public boolean liked(Long userId, String type, Long targetId) {
        if (userId == null) {
            return false;
        }
        if (redisOps.isMember(RedisKeys.socialLikeUser(userId, type), String.valueOf(targetId))) {
            return true;
        }
        return likeMapper.selectCount(new QueryWrapper<LikeRecord>()
                .eq("user_id", userId).eq("target_type", type).eq("target_id", targetId)) > 0;
    }

    private long countOf(String type, Long targetId) {
        return likeMapper.countByTarget(type, targetId);
    }

    /** 批量查询：一次 IN 查询解决列表页 N+1。 */
    public LikeBatchCheckVO batchCheck(Long userId, LikeBatchCheckReq req) {
        String type = targets.requireLikeType(req.getTargetType());
        List<Long> ids = req.getTargetIds().stream().filter(java.util.Objects::nonNull)
                .distinct().limit(200).collect(java.util.stream.Collectors.toList());
        LikeBatchCheckVO vo = new LikeBatchCheckVO();
        vo.setTargetType(type);
        Map<Long, Boolean> liked = new HashMap<>();
        for (Long id : ids) {
            liked.put(id, false);
        }
        if (userId != null && !ids.isEmpty()) {
            for (Long id : likeMapper.selectLikedIds(userId, type, ids)) {
                liked.put(id, true);
            }
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Long id : ids) {
            counts.put(id, 0L);
        }
        if (!ids.isEmpty()) {
            for (LikeCountRow row : likeMapper.countByTargets(type, ids)) {
                counts.put(row.getTargetId(), row.getCnt() == null ? 0L : row.getCnt());
            }
        }
        vo.setLiked(liked);
        vo.setCounts(counts);
        return vo;
    }

    public PageResult<Song> myLikedSongs(Long userId, int pageNum, int pageSize) {
        IPage<Song> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100));
        return PageResult.of(likeMapper.selectLikedSongs(page, userId));
    }

    private static String safeNickname(User user) {
        return user.getNickname() == null ? "用户" : user.getNickname();
    }

    /** 供其它服务读取：某用户对一组目标的点赞状态。 */
    public List<Long> likedIds(Long userId, String type, List<Long> ids) {
        if (userId == null || ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(likeMapper.selectLikedIds(userId, type, ids));
    }
}
