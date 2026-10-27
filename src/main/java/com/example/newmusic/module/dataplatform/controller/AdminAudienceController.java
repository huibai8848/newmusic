package com.example.newmusic.module.dataplatform.controller;

import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.dataplatform.dto.AudienceReq;
import com.example.newmusic.module.dataplatform.service.AudienceService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Map;

/** 人群圈选（BR-26-040 ~ 045）。 */
@Api(tags = "后台接口-人群圈选")
@RestController
@RequestMapping("/api/v1/admin/audience")
public class AdminAudienceController {

    private final AudienceService audienceService;

    public AdminAudienceController(AudienceService audienceService) {
        this.audienceService = audienceService;
    }

    @ApiOperation("人群列表")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<PageResult<Map<String, Object>>> list(PageQuery query) {
        return R.ok(audienceService.list(query));
    }

    @ApiOperation("预览命中人数（不落库）")
    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<Map<String, Object>> preview(@RequestBody AudienceReq req) {
        return R.ok(audienceService.preview(req));
    }

    @ApiOperation("创建人群")
    @PostMapping
    @PreAuthorize("hasAuthority('admin:data:edit')")
    @AdminOpLog(type = "AUDIENCE_CREATE", target = "AUDIENCE", description = "创建人群圈选")
    public R<Map<String, Object>> create(@Valid @RequestBody AudienceReq req) {
        return R.ok(audienceService.create(req));
    }

    @ApiOperation("人群详情（<100 人不返回明细）")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(audienceService.detail(id));
    }

    @ApiOperation("删除人群")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:data:edit')")
    @AdminOpLog(type = "AUDIENCE_DELETE", target = "AUDIENCE", description = "删除人群圈选")
    public R<Map<String, Object>> delete(@PathVariable Long id) {
        return R.ok(audienceService.delete(id));
    }
}
