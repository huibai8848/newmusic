package com.example.newmusic.module.socialupgrade.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.module.social.dto.CommentCreateReq;
import com.example.newmusic.module.social.service.CommentService;
import com.example.newmusic.module.social.support.SocialTargets;
import com.example.newmusic.module.social.vo.CommentVO;
import com.example.newmusic.module.socialupgrade.dto.PostCommentReq;
import com.example.newmusic.module.socialupgrade.dto.PostCreateReq;
import com.example.newmusic.module.socialupgrade.dto.PostUpdateReq;
import com.example.newmusic.module.socialupgrade.dto.RepostReq;
import com.example.newmusic.module.socialupgrade.service.MediaStorageService;
import com.example.newmusic.module.socialupgrade.service.PostService;
import com.example.newmusic.module.socialupgrade.vo.PostVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.Map;

@Api(tags = "社交升级-动态")
@RestController
@RequestMapping("/api/v1/posts")
public class PostController {

    private final PostService postService;
    private final CommentService commentService;
    private final MediaStorageService mediaStorageService;

    public PostController(PostService postService, CommentService commentService,
                          MediaStorageService mediaStorageService) {
        this.postService = postService;
        this.commentService = commentService;
        this.mediaStorageService = mediaStorageService;
    }

    @ApiOperation("动态配图上传（≤9 张由发布接口校验）")
    @PostMapping("/images")
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimitDimension.USER,
            type = "social_post_image")
    public R<String> uploadImage(@RequestParam("file") MultipartFile file) {
        UserContext.requireUserId();
        return R.ok(mediaStorageService.saveImage(file, "post"));
    }

    @ApiOperation("发布动态")
    @PostMapping
    @RateLimit(limit = 20, windowSeconds = 60, dimension = RateLimitDimension.USER, type = "social_post")
    public R<PostVO> create(@Valid @RequestBody PostCreateReq req, HttpServletRequest request) {
        return R.ok(postService.create(UserContext.requireUserId(), req,
                IpUtil.getClientIp(request)));
    }

    @ApiOperation("动态详情")
    @GetMapping("/{id}")
    public R<PostVO> detail(@PathVariable Long id, HttpServletRequest request) {
        return R.ok(postService.detail(UserContext.getUserId(), id,
                IpUtil.getClientIp(request)));
    }

    @ApiOperation("编辑动态（发布后 5 分钟内）")
    @PutMapping("/{id}")
    public R<PostVO> update(@PathVariable Long id, @Valid @RequestBody PostUpdateReq req,
                            HttpServletRequest request) {
        return R.ok(postService.update(UserContext.requireUserId(), id, req,
                IpUtil.getClientIp(request)));
    }

    @ApiOperation("删除动态")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        postService.delete(UserContext.requireUserId(), id);
        return R.ok();
    }

    @ApiOperation("点赞")
    @PostMapping("/{id}/like")
    @RateLimit(limit = 120, windowSeconds = 60, dimension = RateLimitDimension.USER, type = "post_like")
    public R<PostVO> like(@PathVariable Long id) {
        return R.ok(postService.like(UserContext.requireUserId(), id));
    }

    @ApiOperation("取消点赞")
    @DeleteMapping("/{id}/like")
    public R<PostVO> unlike(@PathVariable Long id) {
        return R.ok(postService.unlike(UserContext.requireUserId(), id));
    }

    @ApiOperation("收藏")
    @PostMapping("/{id}/collect")
    public R<PostVO> collect(@PathVariable Long id) {
        return R.ok(postService.collect(UserContext.requireUserId(), id));
    }

    @ApiOperation("取消收藏")
    @DeleteMapping("/{id}/collect")
    public R<PostVO> uncollect(@PathVariable Long id) {
        return R.ok(postService.uncollect(UserContext.requireUserId(), id));
    }

    @ApiOperation("转发")
    @PostMapping("/{id}/repost")
    public R<PostVO> repost(@PathVariable Long id, @RequestBody(required = false) RepostReq req) {
        return R.ok(postService.repost(UserContext.requireUserId(), id, req));
    }

    @ApiOperation("不感兴趣（30 天内不再推荐）")
    @PostMapping("/{id}/dislike")
    public R<Void> dislike(@PathVariable Long id) {
        postService.dislike(UserContext.requireUserId(), id);
        return R.ok();
    }

    @ApiOperation("分享卡片信息")
    @GetMapping("/{id}/share")
    public R<Map<String, Object>> share(@PathVariable Long id) {
        return R.ok(postService.share(UserContext.getUserId(), id));
    }

    @ApiOperation("动态评论列表")
    @GetMapping("/{id}/comments")
    public R<PageResult<CommentVO>> comments(@PathVariable Long id,
                                             @RequestParam(defaultValue = "time") String sort,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return R.ok(commentService.list(UserContext.getUserId(), SocialTargets.POST, id,
                sort, page, size));
    }

    @ApiOperation("发表动态评论")
    @PostMapping("/{id}/comments")
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimitDimension.USER,
            type = "social_comment")
    public R<CommentVO> comment(@PathVariable Long id, @Valid @RequestBody PostCommentReq req,
                                HttpServletRequest request) {
        CommentCreateReq create = new CommentCreateReq();
        create.setTargetType(SocialTargets.POST);
        create.setTargetId(id);
        create.setContent(req.getContent());
        create.setRootId(req.getRootId());
        create.setParentId(req.getParentId());
        return R.ok(commentService.create(UserContext.requireUserId(), create,
                IpUtil.getClientIp(request), request.getHeader("User-Agent")));
    }
}
