package com.example.newmusic.module.contentsafety.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.contentsafety.service.AiLabelService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 生成内容标识（FR-27-010）。
 *
 * <p>歌单实体本身也返回 aiGenerated 字段（前端卡片可直接用）；
 * 这个接口给「只有 ID、没有实体」的详情聚合页补标识。
 */
@Api(tags = "AI 内容标识")
@RestController
@RequestMapping("/api/v1")
public class ContentLabelController {

    private final AiLabelService labelService;

    public ContentLabelController(AiLabelService labelService) {
        this.labelService = labelService;
    }

    @ApiOperation("读取内容 AI 标识")
    @GetMapping("/content/labels")
    public R<Map<String, Object>> labels(@RequestParam String contentType,
                                         @RequestParam Long contentId) {
        return R.ok(labelService.labels(contentType, contentId));
    }

    @ApiOperation("标记歌单为 AI 生成并触发审核（后台）")
    @PostMapping("/admin/playlists/{id}/ai-label")
    @PreAuthorize("hasAuthority('admin:audit:edit')")
    @AdminOpLog(type = "AI_LABEL_MARK", target = "PLAYLIST", description = "标记 AI 生成歌单")
    public R<Map<String, Object>> markPlaylist(@PathVariable Long id,
                                               @RequestParam(required = false) String model,
                                               @RequestParam(required = false) String promptVersion) {
        return R.ok(labelService.markPlaylist(id, model, promptVersion));
    }

    @ApiOperation("删除 AI 标识（任何角色都禁止，返回 72024）")
    @PostMapping("/admin/content/labels/remove")
    @PreAuthorize("hasAuthority('admin:audit:edit')")
    public R<Void> remove() {
        labelService.forbidRemoval();
        return R.ok();
    }
}
