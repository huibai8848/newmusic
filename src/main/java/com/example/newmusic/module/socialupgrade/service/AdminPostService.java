package com.example.newmusic.module.socialupgrade.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.socialupgrade.dto.PostAuditReq;
import com.example.newmusic.module.socialupgrade.entity.PostMention;
import com.example.newmusic.module.socialupgrade.entity.UserPost;
import com.example.newmusic.module.socialupgrade.mapper.UserPostMapper;
import com.example.newmusic.module.socialupgrade.vo.PostVO;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.social.service.SocialNotifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 后台动态审核与话题管理（FR-25-003 人工复核 / P1 运营）。 */
@Service
public class AdminPostService {

    private final UserPostMapper postMapper;
    private final PostAssembler assembler;
    private final MentionService mentionService;
    private final FeedService feedService;
    private final SocialNotifier notifier;
    private final SocialUserSupport users;

    public AdminPostService(UserPostMapper postMapper, PostAssembler assembler,
                            MentionService mentionService, FeedService feedService,
                            SocialNotifier notifier, SocialUserSupport users) {
        this.postMapper = postMapper;
        this.assembler = assembler;
        this.mentionService = mentionService;
        this.feedService = feedService;
        this.notifier = notifier;
        this.users = users;
    }

    public PageResult<PostVO> pending(int pageNum, int pageSize) {
        int size = Math.min(Math.max(1, pageSize), 100);
        int index = Math.max(1, pageNum);
        long total = postMapper.countPending();
        PageResult<PostVO> result = new PageResult<>();
        result.setPageNum(index);
        result.setPageSize(size);
        result.setTotal(total);
        result.setPages((int) ((total + size - 1) / size));
        result.setHasNext((long) index * size < total);
        result.setTruncated(false);
        if (total == 0) {
            result.setList(java.util.Collections.emptyList());
            return result;
        }
        List<UserPost> rows = postMapper.selectPending((long) (index - 1) * size, size);
        result.setList(assembler.assemble(rows, null));
        return result;
    }

    public PageResult<PostVO> list(Integer status, Long userId, String keyword,
                                   int pageNum, int pageSize) {
        Page<UserPost> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100));
        QueryWrapper<UserPost> wrapper = new QueryWrapper<>();
        if (status != null) {
            wrapper.eq("status", status);
        }
        if (userId != null) {
            wrapper.eq("user_id", userId);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like("content", keyword.trim());
        }
        wrapper.orderByDesc("create_time").orderByDesc("id");
        Page<UserPost> result = postMapper.selectPage(page, wrapper);
        List<PostVO> vos = assembler.assemble(result.getRecords(), null);
        return PageResult.ofList(result, vos);
    }

    @Transactional
    public PostVO audit(Long postId, PostAuditReq req) {
        UserPost post = postMapper.selectById(postId);
        if (post == null || Integer.valueOf(UserPost.STATUS_DELETED).equals(post.getStatus())) {
            throw new BizException(ResultCode.POST_NOT_FOUND);
        }
        boolean pass = Boolean.TRUE.equals(req.getPass());
        String reason = req.getReason() == null ? null : req.getReason().trim();
        if (!pass && (reason == null || reason.length() < 5)) {
            throw new BizException(ResultCode.PARAM_INVALID, "拒绝时必须填写不少于 5 个字符的理由");
        }
        if (pass) {
            postMapper.updateAudit(postId, UserPost.STATUS_PUBLISHED, 1, null);
            post.setStatus(UserPost.STATUS_PUBLISHED);
            post.setAuditStatus(1);
            // 审核通过后才把 @ 生效并进流，避免仅自己可见阶段提前通知他人
            User author = users.loadUsers(java.util.Collections.singletonList(post.getUserId()))
                    .get(post.getUserId());
            if (author != null) {
                mentionService.deleteBySource(PostMention.SOURCE_POST, postId);
                mentionService.save(author, PostMention.SOURCE_POST, postId, post.getContent(), null);
            }
            if (!Integer.valueOf(UserPost.VISIBILITY_SELF).equals(post.getVisibility())) {
                feedService.distribute(post);
            }
            notify(post, "动态审核通过", "你的动态已通过审核");
        } else {
            postMapper.updateAudit(postId, UserPost.STATUS_SELF_ONLY, 2, reason);
            post.setStatus(UserPost.STATUS_SELF_ONLY);
            post.setAuditStatus(2);
            notify(post, "动态审核未通过", "你的动态未通过审核，仅自己可见：" + reason);
        }
        return assembler.one(post, null);
    }

    @Transactional
    public void adminDelete(Long postId, String reason) {
        UserPost post = postMapper.selectById(postId);
        if (post == null) {
            return;
        }
        if (Integer.valueOf(UserPost.STATUS_ADMIN_DELETED).equals(post.getStatus())) {
            return;
        }
        // 保留 deleted=0，让后台仍能看到被处置记录；公开接口按 status=4 全部过滤
        postMapper.updateAudit(postId, UserPost.STATUS_ADMIN_DELETED, 2, reason);
        mentionService.deleteBySource(PostMention.SOURCE_POST, postId);
        notify(post, "动态已被删除", "你的动态因违反社区规范已被删除"
                + (StringUtils.hasText(reason) ? "：" + reason : ""));
    }

    private void notify(UserPost post, String title, String content) {
        User author = users.loadUsers(java.util.Collections.singletonList(post.getUserId()))
                .get(post.getUserId());
        notifier.notify(post.getUserId(), Notification.TYPE_AUDIT, title, content,
                null, null, Notification.TARGET_POST, post.getId());
    }

    public long countByStatus(Integer status) {
        QueryWrapper<UserPost> wrapper = new QueryWrapper<>();
        if (status != null) {
            wrapper.eq("status", status);
        }
        return postMapper.selectCount(wrapper);
    }
}
