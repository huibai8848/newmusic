package com.example.newmusic.module.social.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.contentsafety.dto.AuditRequest;
import com.example.newmusic.module.contentsafety.dto.AuditResult;
import com.example.newmusic.module.contentsafety.service.ContentAuditService;
import com.example.newmusic.module.contentsafety.service.MinorProtectionService;
import com.example.newmusic.module.contentsafety.service.UserRestrictionService;
import com.example.newmusic.module.search.support.SensitiveWordSupport;
import com.example.newmusic.module.social.config.SocialProperties;
import com.example.newmusic.module.social.dto.CommentCreateReq;
import com.example.newmusic.module.social.entity.Comment;
import com.example.newmusic.module.social.entity.PrivacySetting;
import com.example.newmusic.module.social.mapper.BlacklistMapper;
import com.example.newmusic.module.social.mapper.CommentMapper;
import com.example.newmusic.module.social.support.ContentSanitizer;
import com.example.newmusic.module.social.support.SocialTargets;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.social.vo.CommentVO;
import com.example.newmusic.module.socialupgrade.service.IntimacyService;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.ws.entity.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评论（模块 10 FR-19-001~003）。
 */
@Slf4j
@Service
public class CommentService {

    private static final int MAX_REPLIES_PER_ROOT = 50;

    private final CommentMapper commentMapper;
    private final BlacklistMapper blacklistMapper;
    private final SocialTargets targets;
    private final SocialUserSupport users;
    private final ContentSanitizer sanitizer;
    private final SensitiveWordSupport sensitiveWordSupport;
    private final SocialNotifier notifier;
    private final LikeService likeService;
    private final PrivacySettingService privacySettingService;
    private final SocialProperties properties;
    private final IntimacyService intimacyService;
    private final ContentAuditService contentAuditService;
    private final UserRestrictionService restrictionService;
    private final MinorProtectionService minorService;

    public CommentService(CommentMapper commentMapper, BlacklistMapper blacklistMapper,
                          SocialTargets targets, SocialUserSupport users, ContentSanitizer sanitizer,
                          SensitiveWordSupport sensitiveWordSupport, SocialNotifier notifier,
                          LikeService likeService, PrivacySettingService privacySettingService,
                          SocialProperties properties, IntimacyService intimacyService,
                          ContentAuditService contentAuditService,
                          UserRestrictionService restrictionService,
                          MinorProtectionService minorService) {
        this.commentMapper = commentMapper;
        this.blacklistMapper = blacklistMapper;
        this.targets = targets;
        this.users = users;
        this.sanitizer = sanitizer;
        this.sensitiveWordSupport = sensitiveWordSupport;
        this.notifier = notifier;
        this.likeService = likeService;
        this.privacySettingService = privacySettingService;
        this.properties = properties;
        this.intimacyService = intimacyService;
        this.contentAuditService = contentAuditService;
        this.restrictionService = restrictionService;
        this.minorService = minorService;
    }

    @Transactional
    public CommentVO create(Long userId, CommentCreateReq req, String ip, String userAgent) {
        User user = users.requireUser(userId);
        if (users.isBanned(user)) {
            throw new BizException(ResultCode.ACCOUNT_BANNED);
        }

        String content = sanitizer.sanitizeComment(req.getContent());
        restrictionService.requireCanPublish(userId);
        minorService.requireSocialAllowed(userId);
        if (sensitiveWordSupport.isBlocked(content)) {
            throw new BizException(ResultCode.COMMENT_ILLEGAL);
        }
        // 模块 18：DFA/规则机审；REJECT 直接拒绝，REVIEW 落库后转人工。
        AuditRequest auditRequest = AuditRequest.of("COMMENT", null, content, userId, "COMMENT");
        AuditResult audit = contentAuditService.audit(auditRequest);
        if (audit.isReject()) {
            throw new BizException(ResultCode.COMMENT_ILLEGAL);
        }

        LocalDateTime now = LocalDateTime.now();
        if (commentMapper.countSince(userId, now.minusHours(1)) >= 30
                || commentMapper.countSince(userId, now.minusDays(1)) >= 200) {
            throw new BizException(ResultCode.COMMENT_TOO_FREQUENT);
        }

        String type = targets.requireCommentType(req.getTargetType());
        Long targetId = req.getTargetId();
        // 先把 COMMENT 目标解析成它所属的内容，回复才能出现在内容评论区
        Comment targetComment = null;
        if (SocialTargets.COMMENT.equals(type)) {
            targetComment = commentMapper.selectById(targetId);
            if (targetComment == null || !Integer.valueOf(Comment.STATUS_PUBLISHED).equals(targetComment.getStatus())) {
                throw new BizException(ResultCode.COMMENT_NOT_FOUND);
            }
            type = targetComment.getTargetType();
            targetId = targetComment.getTargetId();
        }

        SocialTargets.TargetInfo info = targets.require(type, targetId, true);
        checkTargetVisible(info, userId);
        checkCommentPolicy(info, userId);

        if (commentMapper.countSameContent(userId, type, targetId, content, now.minusMinutes(5)) > 0) {
            throw new BizException(ResultCode.COMMENT_DUPLICATED);
        }

        long rootId = 0;
        long parentId = 0;
        Long replyUserId = null;

        if (targetComment != null) {
            rootId = targetComment.getRootId() != null && targetComment.getRootId() > 0
                    ? targetComment.getRootId() : targetComment.getId();
            parentId = req.getParentId() != null ? req.getParentId() : targetComment.getId();
            replyUserId = targetComment.getUserId();
        } else if (req.getRootId() != null && req.getRootId() > 0) {
            Comment root = commentMapper.selectById(req.getRootId());
            if (root == null || !Objects.equals(root.getTargetType(), type)
                    || !Objects.equals(root.getTargetId(), targetId)
                    || (root.getRootId() != null && root.getRootId() > 0)) {
                throw new BizException(ResultCode.COMMENT_NOT_FOUND);
            }
            rootId = root.getId();
            parentId = req.getParentId() != null ? req.getParentId() : root.getId();
            Comment parent = commentMapper.selectById(parentId);
            if (parent == null || !Objects.equals(parent.getRootId(), rootId)
                    || !Integer.valueOf(Comment.STATUS_PUBLISHED).equals(parent.getStatus())) {
                throw new BizException(ResultCode.COMMENT_NOT_FOUND);
            }
            replyUserId = parent.getUserId();
        }

        int status = Comment.STATUS_PUBLISHED;
        if (audit.isReview() || (isNewUser(user) && !users.isStaff(user))) {
            status = Comment.STATUS_PENDING;
        }

        Comment comment = new Comment();
        comment.setTargetType(type);
        comment.setTargetId(targetId);
        comment.setUserId(userId);
        comment.setRootId(rootId);
        comment.setParentId(parentId);
        comment.setReplyUserId(replyUserId);
        comment.setContent(content);
        comment.setLikeCount(0);
        comment.setReplyCount(0);
        comment.setStatus(status);
        comment.setIp(ip);
        comment.setUserAgent(userAgent == null ? null
                : userAgent.substring(0, Math.min(userAgent.length(), 500)));
        commentMapper.insert(comment);
        // 审核记录先于建任务落库，这里只补 content_id 与人工任务。
        contentAuditService.attachContent(audit, auditRequest, comment.getId());

        if (status == Comment.STATUS_PUBLISHED) {
            targets.addCommentCount(type, targetId, 1);
            if (rootId > 0) {
                commentMapper.updateReplyCount(rootId, 1);
            }
            notifyComment(user, type, targetId, comment, replyUserId);
            // 评论动态计入与动态作者的亲密度（权重 3，PRD 25 FR-25-011）
            if (SocialTargets.POST.equals(type) && info.getOwnerId() != null) {
                intimacyService.record(userId, info.getOwnerId(), IntimacyService.WEIGHT_COMMENT);
            }
        }

        return toVO(comment, Collections.singletonMap(userId, user),
                Collections.emptySet(), userId);
    }

    /** 顶级评论分页；hot = 点赞×7 + 回复×3。 */
    public PageResult<CommentVO> list(Long viewerId, String rawType, Long targetId,
                                      String sort, int pageNum, int pageSize) {
        String type = targets.requireCommentType(rawType);
        SocialTargets.TargetInfo info = targets.require(type, targetId, false);
        checkTargetVisible(info, viewerId);
        if (SocialTargets.COMMENT.equals(type)) {
            throw new BizException(ResultCode.COMMENT_NOT_FOUND, "请按内容查询评论");
        }

        Page<Comment> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100));
        QueryWrapper<Comment> wrapper = rootQuery(viewerId, type, targetId);
        if ("hot".equalsIgnoreCase(sort)) {
            wrapper.last("ORDER BY (like_count * 7 + reply_count * 3) DESC, create_time DESC, id DESC");
        } else {
            wrapper.orderByDesc("create_time").orderByDesc("id");
        }
        Page<Comment> result = commentMapper.selectPage(page, wrapper);
        return pageOf(result, toVOList(result.getRecords(), viewerId, true));
    }

    public PageResult<CommentVO> replies(Long viewerId, Long rootId, int pageNum, int pageSize) {
        Comment root = rootId == null ? null : commentMapper.selectById(rootId);
        if (root == null || (root.getRootId() != null && root.getRootId() > 0)) {
            throw new BizException(ResultCode.COMMENT_NOT_FOUND);
        }
        SocialTargets.TargetInfo info = targets.require(root.getTargetType(), root.getTargetId(), false);
        checkTargetVisible(info, viewerId);

        int size = Math.min(Math.max(1, pageSize), MAX_REPLIES_PER_ROOT);
        int num = Math.max(1, pageNum);
        if ((long) (num - 1) * size >= MAX_REPLIES_PER_ROOT) {
            return PageResult.empty(num, size);
        }

        Page<Comment> page = new Page<>(num, size);
        QueryWrapper<Comment> wrapper = new QueryWrapper<>();
        wrapper.eq("root_id", rootId);
        applyVisibility(wrapper, viewerId);
        applyBlacklistFilter(wrapper, viewerId);
        wrapper.orderByAsc("create_time").orderByAsc("id");
        Page<Comment> result = commentMapper.selectPage(page, wrapper);
        return pageOf(result, toVOList(result.getRecords(), viewerId, false));
    }

    /**
     * 删除评论。幂等：已经删掉的直接返回。
     * 作者本人或运营/管理员可删；后台删除原因通过通知另行下发（模块 09 流程）。
     */
    @Transactional
    public void delete(Long userId, Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            return;
        }
        User operator = users.requireUser(userId);
        boolean mine = userId.equals(comment.getUserId());
        boolean staff = users.isStaff(operator)
                || (UserContext.isLogin() && UserContext.hasPermission("admin:content:delete"));
        if (!mine && !staff) {
            throw new BizException(ResultCode.NO_PERMISSION);
        }

        if (comment.getRootId() != null && comment.getRootId() > 0) {
            commentMapper.softDelete(comment.getId());
            if (Integer.valueOf(Comment.STATUS_PUBLISHED).equals(comment.getStatus())) {
                commentMapper.updateReplyCount(comment.getRootId(), -1);
                targets.addCommentCount(comment.getTargetType(), comment.getTargetId(), -1);
            }
            return;
        }

        int publishedReplies = commentMapper.countPublishedReplies(comment.getId());
        commentMapper.softDelete(comment.getId());
        commentMapper.softDeleteReplies(comment.getId());
        if (Integer.valueOf(Comment.STATUS_PUBLISHED).equals(comment.getStatus())) {
            targets.addCommentCount(comment.getTargetType(), comment.getTargetId(), -1 - publishedReplies);
        }
    }

    // ------------------------------------------------------------------ helpers

    private QueryWrapper<Comment> rootQuery(Long viewerId, String type, Long targetId) {
        QueryWrapper<Comment> wrapper = new QueryWrapper<>();
        wrapper.eq("target_type", type).eq("target_id", targetId).eq("root_id", 0);
        applyVisibility(wrapper, viewerId);
        applyBlacklistFilter(wrapper, viewerId);
        return wrapper;
    }

    /** 已发布对所有人可见；待审只对作者本人可见。 */
    private void applyVisibility(QueryWrapper<Comment> wrapper, Long viewerId) {
        if (viewerId == null) {
            wrapper.eq("status", Comment.STATUS_PUBLISHED);
        } else {
            wrapper.and(w -> w.eq("status", Comment.STATUS_PUBLISHED)
                    .or(o -> o.eq("status", Comment.STATUS_PENDING).eq("user_id", viewerId)));
        }
    }

    /** 双向拉黑过滤（PRD FR-19-010），在 SQL 里做，保证分页 total 准确。 */
    private void applyBlacklistFilter(QueryWrapper<Comment> wrapper, Long viewerId) {
        if (viewerId == null) {
            return;
        }
        Set<Long> excluded = new HashSet<>(blacklistMapper.selectBlockedIds(viewerId));
        excluded.addAll(blacklistMapper.selectBlockerIds(viewerId));
        excluded.remove(viewerId);
        if (!excluded.isEmpty()) {
            wrapper.notIn("user_id", excluded);
        }
    }

    private List<CommentVO> toVOList(List<Comment> comments, Long viewerId, boolean withReplies) {
        if (comments == null || comments.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = comments.stream().map(Comment::getId).collect(Collectors.toList());
        Set<Long> liked = new HashSet<>(likeService.likedIds(viewerId, SocialTargets.COMMENT, ids));

        List<Comment> replies = Collections.emptyList();
        if (withReplies) {
            replies = commentMapper.selectFirstReplies(ids, 2);
        }

        Set<Long> userIds = new HashSet<>();
        comments.forEach(c -> {
            userIds.add(c.getUserId());
            if (c.getReplyUserId() != null) {
                userIds.add(c.getReplyUserId());
            }
        });
        replies.forEach(r -> {
            userIds.add(r.getUserId());
            if (r.getReplyUserId() != null) {
                userIds.add(r.getReplyUserId());
            }
        });
        Map<Long, User> userMap = users.loadUsers(userIds);

        Map<Long, List<Comment>> replyMap = replies.stream()
                .collect(Collectors.groupingBy(Comment::getRootId, LinkedHashMap::new, Collectors.toList()));

        List<CommentVO> out = new ArrayList<>();
        for (Comment comment : comments) {
            CommentVO vo = toVO(comment, userMap, liked, viewerId);
            List<Comment> children = replyMap.get(comment.getId());
            if (children != null && !children.isEmpty()) {
                vo.setReplies(children.stream()
                        .map(child -> toVO(child, userMap,
                                new HashSet<>(likeService.likedIds(viewerId, SocialTargets.COMMENT,
                                        Collections.singletonList(child.getId()))), viewerId))
                        .collect(Collectors.toList()));
            } else {
                vo.setReplies(Collections.emptyList());
            }
            out.add(vo);
        }
        return out;
    }

    private CommentVO toVO(Comment comment, Map<Long, User> userMap, Set<Long> liked, Long viewerId) {
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setTargetType(comment.getTargetType());
        vo.setTargetId(comment.getTargetId());
        vo.setUserId(comment.getUserId());
        User author = userMap.get(comment.getUserId());
        if (author != null) {
            vo.setNickname(author.getNickname());
            vo.setAvatar(author.getAvatar());
        }
        vo.setRootId(comment.getRootId());
        vo.setParentId(comment.getParentId());
        vo.setReplyUserId(comment.getReplyUserId());
        User replied = comment.getReplyUserId() == null ? null : userMap.get(comment.getReplyUserId());
        vo.setReplyNickname(replied == null ? null : replied.getNickname());
        vo.setContent(comment.getContent());
        vo.setLikeCount(comment.getLikeCount());
        vo.setReplyCount(comment.getReplyCount());
        vo.setLiked(liked.contains(comment.getId()));
        vo.setStatus(comment.getStatus());
        vo.setMine(viewerId != null && viewerId.equals(comment.getUserId()));
        vo.setCreateTime(comment.getCreateTime());
        return vo;
    }

    private void notifyComment(User from, String type, Long targetId, Comment comment, Long replyUserId) {
        if (replyUserId != null) {
            notifier.notify(replyUserId, Notification.TYPE_COMMENT, "收到新回复",
                    excerpt(from.getNickname()) + " 回复了你的评论：" + excerpt(comment.getContent()),
                    from.getId(), from, Notification.TARGET_COMMENT, comment.getParentId());
            return;
        }
        if (SocialTargets.POST.equals(type)) {
            SocialTargets.TargetInfo info = targets.require(type, targetId, false);
            if (info.getOwnerId() != null) {
                notifier.notify(info.getOwnerId(), Notification.TYPE_COMMENT, "收到新评论",
                        excerpt(from.getNickname()) + " 评论了你的动态：" + excerpt(comment.getContent()),
                        from.getId(), from, Notification.TARGET_POST, targetId);
            }
            return;
        }
        if (SocialTargets.PLAYLIST.equals(type) || SocialTargets.ROOM.equals(type)) {
            SocialTargets.TargetInfo info = targets.require(type, targetId, false);
            if (info.getOwnerId() != null) {
                notifier.notify(info.getOwnerId(), Notification.TYPE_COMMENT, "收到新评论",
                        excerpt(from.getNickname()) + " 评论了你的"
                                + (SocialTargets.PLAYLIST.equals(type) ? "歌单" : "房间") + "："
                                + excerpt(comment.getContent()),
                        from.getId(), from, notifyTargetType(type), targetId);
            }
        }
    }

    private void checkTargetVisible(SocialTargets.TargetInfo info, Long viewerId) {
        if (SocialTargets.POST.equals(info.getTargetType())) {
            boolean owner = Objects.equals(info.getOwnerId(), viewerId);
            if (owner) {
                return;
            }
            if (viewerId != null && users.blockedEither(viewerId, info.getOwnerId())) {
                throw new BizException(ResultCode.USER_BLACKLISTED);
            }
            int visibility = info.getVisibility() == null ? 0 : info.getVisibility();
            if (visibility == 2) {
                throw new BizException(ResultCode.POST_NOT_VISIBLE);
            }
            if (visibility == 1 && !users.isFollowing(viewerId, info.getOwnerId())) {
                throw new BizException(ResultCode.POST_NOT_VISIBLE);
            }
            return;
        }
        if (info.isPrivatePlaylist() && !Objects.equals(info.getOwnerId(), viewerId)) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND);
        }
    }

    /** 歌单/房间归属人的「谁可以评论我」设置。 */
    private void checkCommentPolicy(SocialTargets.TargetInfo info, Long viewerId) {
        if (!(SocialTargets.PLAYLIST.equals(info.getTargetType())
                || SocialTargets.ROOM.equals(info.getTargetType()))) {
            return;
        }
        Long ownerId = info.getOwnerId();
        if (ownerId == null || ownerId.equals(viewerId)) {
            return;
        }
        if (users.blockedEither(viewerId, ownerId)) {
            throw new BizException(ResultCode.USER_BLACKLISTED);
        }
        PrivacySetting privacy = privacySettingService.get(ownerId);
        if (Integer.valueOf(2).equals(privacy.getCommentPolicy())) {
            throw new BizException(ResultCode.COMMENT_DISABLED);
        }
        if (Integer.valueOf(1).equals(privacy.getCommentPolicy())
                && !users.isFollowing(viewerId, ownerId)) {
            throw new BizException(ResultCode.COMMENT_DISABLED);
        }
    }

    private boolean isNewUser(User user) {
        return user.getCreateTime() != null
                && user.getCreateTime().isAfter(LocalDateTime.now()
                .minusDays(properties.getComment().getNewUserAuditDays()));
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 30 ? text : text.substring(0, 30) + "…";
    }

    private static int notifyTargetType(String type) {
        switch (type) {
            case SocialTargets.SONG:
                return Notification.TARGET_SONG;
            case SocialTargets.PLAYLIST:
                return Notification.TARGET_PLAYLIST;
            case SocialTargets.ALBUM:
                return Notification.TARGET_ALBUM;
            case SocialTargets.ROOM:
                return Notification.TARGET_ROOM;
            default:
                return Notification.TARGET_COMMENT;
        }
    }

    /** 管理端内容审核通过/拒绝时调用（模块 09 的评论审核接入点）。 */
    @Transactional
    public void audit(Long commentId, boolean pass, String reason) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            return;
        }
        int from = comment.getStatus() == null ? Comment.STATUS_PENDING : comment.getStatus();
        if (from == Comment.STATUS_PUBLISHED) {
            return;
        }
        comment.setStatus(pass ? Comment.STATUS_PUBLISHED : Comment.STATUS_BLOCKED);
        comment.setAuditReason(reason);
        commentMapper.updateById(comment);
        if (pass) {
            targets.addCommentCount(comment.getTargetType(), comment.getTargetId(), 1);
            if (comment.getRootId() != null && comment.getRootId() > 0) {
                commentMapper.updateReplyCount(comment.getRootId(), 1);
            }
        }
        User author = users.loadUsers(Collections.singletonList(comment.getUserId()))
                .get(comment.getUserId());
        notifier.notify(comment.getUserId(), Notification.TYPE_AUDIT,
                pass ? "评论审核通过" : "评论未通过审核",
                pass ? "你的评论已通过审核" : "你的评论未通过审核：" + (reason == null ? "违反社区规范" : reason),
                null, author, Notification.TARGET_COMMENT, comment.getId());
    }

    /** 前端展示：某内容的评论总数（已发布）。 */
    public long countPublished(String type, Long targetId) {
        return commentMapper.selectCount(new QueryWrapper<Comment>()
                .eq("target_type", type).eq("target_id", targetId)
                .eq("status", Comment.STATUS_PUBLISHED));
    }

    private static <T> PageResult<T> pageOf(Page<Comment> page, List<T> list) {
        PageResult<T> r = new PageResult<>();
        r.setList(list);
        r.setTotal(page.getTotal());
        r.setPageNum(page.getCurrent());
        r.setPageSize(page.getSize());
        r.setPages(page.getPages());
        r.setHasNext(page.getCurrent() < page.getPages());
        r.setTruncated(false);
        return r;
    }
}
