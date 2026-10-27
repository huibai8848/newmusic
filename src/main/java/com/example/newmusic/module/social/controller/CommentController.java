package com.example.newmusic.module.social.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.module.social.dto.CommentCreateReq;
import com.example.newmusic.module.social.service.CommentService;
import com.example.newmusic.module.social.vo.CommentVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@Api(tags = "社交-评论")
@RestController
@RequestMapping("/api/v1/comments")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @ApiOperation(value = "发表评论/回复", notes = "两级结构：传 rootId/parentId 即为回复；新用户评论可能先审后发。")
    @PostMapping
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimitDimension.USER,
            type = "social_comment")
    public R<CommentVO> create(@Valid @RequestBody CommentCreateReq req,
                               HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        return R.ok(commentService.create(UserContext.requireUserId(), req,
                IpUtil.getClientIp(request), ua));
    }

    @ApiOperation(value = "评论列表", notes = "公开接口。游客也可查看，登录后返回自己的点赞状态。")
    @GetMapping
    @RateLimit(limit = 120, windowSeconds = 60, type = "social_comment")
    public R<PageResult<CommentVO>> list(@RequestParam String targetType,
                                         @RequestParam Long targetId,
                                         @RequestParam(defaultValue = "time") String sort,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return R.ok(commentService.list(UserContext.getUserId(), targetType, targetId,
                sort, page, size));
    }

    @ApiOperation("回复列表")
    @GetMapping("/{id}/replies")
    @RateLimit(limit = 120, windowSeconds = 60, type = "social_comment")
    public R<PageResult<CommentVO>> replies(@PathVariable Long id,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return R.ok(commentService.replies(UserContext.getUserId(), id, page, size));
    }

    @ApiOperation("删除评论（作者或管理员）")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        commentService.delete(UserContext.requireUserId(), id);
        return R.ok();
    }
}
