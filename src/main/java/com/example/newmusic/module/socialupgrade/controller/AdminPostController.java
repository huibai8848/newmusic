package com.example.newmusic.module.socialupgrade.controller;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.socialupgrade.dto.PostAuditReq;
import com.example.newmusic.module.socialupgrade.entity.Topic;
import com.example.newmusic.module.socialupgrade.dto.TopicReq;
import com.example.newmusic.module.socialupgrade.service.AdminPostService;
import com.example.newmusic.module.socialupgrade.service.TopicService;
import com.example.newmusic.module.socialupgrade.vo.PostVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@Api(tags = "后台接口-动态与话题")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminPostController {

    private final AdminPostService adminPostService;
    private final TopicService topicService;

    public AdminPostController(AdminPostService adminPostService, TopicService topicService) {
        this.adminPostService = adminPostService;
        this.topicService = topicService;
    }

    @ApiOperation("待审核动态")
    @GetMapping("/posts/pending")
    @PreAuthorize("hasAuthority('admin:content:view')")
    public R<PageResult<PostVO>> pending(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return R.ok(adminPostService.pending(page, size));
    }

    @ApiOperation("动态列表（运营查询）")
    @GetMapping("/posts")
    @PreAuthorize("hasAuthority('admin:content:view')")
    public R<PageResult<PostVO>> list(@RequestParam(required = false) Integer status,
                                      @RequestParam(required = false) Long userId,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return R.ok(adminPostService.list(status, userId, keyword, page, size));
    }

    @ApiOperation("审核动态")
    @PostMapping("/posts/{id}/audit")
    @PreAuthorize("hasAuthority('admin:content:audit')")
    @AdminOpLog(type = "POST_AUDIT", target = "USER_POST", description = "审核用户动态")
    public R<PostVO> audit(@PathVariable Long id, @Valid @RequestBody PostAuditReq req) {
        return R.ok(adminPostService.audit(id, req));
    }

    @ApiOperation("删除动态（管理员）")
    @DeleteMapping("/posts/{id}")
    @PreAuthorize("hasAuthority('admin:content:edit')")
    @AdminOpLog(type = "POST_DELETE", target = "USER_POST", description = "管理员删除动态")
    public R<Void> delete(@PathVariable Long id,
                          @RequestParam(required = false) String reason) {
        adminPostService.adminDelete(id, reason);
        return R.ok();
    }

    @ApiOperation("创建话题")
    @PostMapping("/topics")
    @PreAuthorize("hasAuthority('admin:content:edit')")
    @AdminOpLog(type = "TOPIC_CREATE", target = "TOPIC", description = "创建话题")
    public R<Topic> createTopic(@Valid @RequestBody TopicReq req) {
        return R.ok(topicService.create(req));
    }

    @ApiOperation("更新话题")
    @PutMapping("/topics/{id}")
    @PreAuthorize("hasAuthority('admin:content:edit')")
    @AdminOpLog(type = "TOPIC_UPDATE", target = "TOPIC", description = "更新话题")
    public R<Topic> updateTopic(@PathVariable Long id, @Valid @RequestBody TopicReq req) {
        return R.ok(topicService.update(id, req));
    }
}
