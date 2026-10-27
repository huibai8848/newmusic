package com.example.newmusic.module.social.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.module.social.dto.CollectMoveReq;
import com.example.newmusic.module.social.dto.CollectReq;
import com.example.newmusic.module.social.dto.FolderSaveReq;
import com.example.newmusic.module.social.service.CollectService;
import com.example.newmusic.module.social.vo.CollectFolderVO;
import com.example.newmusic.module.social.vo.CollectVO;
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

import javax.validation.Valid;
import java.util.List;

@Api(tags = "社交-收藏")
@RestController
@RequestMapping("/api/v1")
public class CollectController {

    private final CollectService collectService;

    public CollectController(CollectService collectService) {
        this.collectService = collectService;
    }

    @ApiOperation("收藏/取消收藏")
    @PostMapping("/collects")
    @RateLimit(limit = 120, windowSeconds = 60, dimension = RateLimitDimension.USER, type = "social_collect")
    public R<CollectVO> toggle(@Valid @RequestBody CollectReq req) {
        return R.ok(collectService.toggle(UserContext.requireUserId(), req));
    }

    @ApiOperation("取消收藏")
    @DeleteMapping("/collects")
    public R<CollectVO> cancel(@Valid @RequestBody CollectReq req) {
        req.setCollect(false);
        return R.ok(collectService.toggle(UserContext.requireUserId(), req));
    }

    @ApiOperation("查询收藏状态")
    @GetMapping("/collects/status")
    public R<CollectVO> status(@RequestParam String targetType,
                               @RequestParam Long targetId) {
        return R.ok(collectService.status(UserContext.requireUserId(), targetType, targetId));
    }

    @ApiOperation("我的收藏")
    @GetMapping("/collects")
    public R<PageResult<CollectVO>> list(@RequestParam(required = false) String targetType,
                                         @RequestParam(required = false) Long folderId,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return R.ok(collectService.list(UserContext.requireUserId(), targetType, folderId, page, size));
    }

    @ApiOperation("移动收藏到收藏夹")
    @PutMapping("/collects/move")
    public R<Integer> move(@Valid @RequestBody CollectMoveReq req) {
        return R.ok(collectService.move(UserContext.requireUserId(), req));
    }

    @ApiOperation("收藏夹列表（含默认收藏夹）")
    @GetMapping("/collect-folders")
    public R<List<CollectFolderVO>> folders() {
        return R.ok(collectService.folders(UserContext.requireUserId()));
    }

    @ApiOperation("新建收藏夹")
    @PostMapping("/collect-folders")
    public R<CollectFolderVO> createFolder(@Valid @RequestBody FolderSaveReq req) {
        return R.ok(collectService.createFolder(UserContext.requireUserId(), req));
    }

    @ApiOperation("重命名收藏夹")
    @PutMapping("/collect-folders/{id}")
    public R<CollectFolderVO> renameFolder(@PathVariable Long id,
                                           @Valid @RequestBody FolderSaveReq req) {
        return R.ok(collectService.renameFolder(UserContext.requireUserId(), id, req));
    }

    @ApiOperation(value = "删除收藏夹", notes = "夹内收藏移入默认收藏夹，收藏关系不删除。")
    @DeleteMapping("/collect-folders/{id}")
    public R<Void> deleteFolder(@PathVariable Long id) {
        collectService.deleteFolder(UserContext.requireUserId(), id);
        return R.ok();
    }
}
