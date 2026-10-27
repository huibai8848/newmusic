package com.example.newmusic.module.dataplatform.controller;

import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.dataplatform.dto.EventDefSaveReq;
import com.example.newmusic.module.dataplatform.dto.TrackEventReq;
import com.example.newmusic.module.dataplatform.entity.EventDef;
import com.example.newmusic.module.dataplatform.service.EventDefService;
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
import java.util.Map;

/** 埋点定义管理（BR-26-001：先文档后埋点）。 */
@Api(tags = "后台接口-埋点管理")
@RestController
@RequestMapping("/api/v1/admin/events")
public class AdminEventDefController {

    private final EventDefService eventDefService;

    public AdminEventDefController(EventDefService eventDefService) {
        this.eventDefService = eventDefService;
    }

    @ApiOperation("埋点定义列表")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<PageResult<EventDef>> list(PageQuery query,
                                        @RequestParam(required = false) String domain,
                                        @RequestParam(required = false) Integer status) {
        return R.ok(eventDefService.list(query, domain, status));
    }

    @ApiOperation("埋点定义详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<EventDef> get(@PathVariable Long id) {
        return R.ok(eventDefService.get(id));
    }

    @ApiOperation("新建埋点定义")
    @PostMapping
    @PreAuthorize("hasAuthority('admin:data:edit')")
    @AdminOpLog(type = "EVENT_DEF_CREATE", target = "EVENT_DEF", description = "新建埋点定义")
    public R<EventDef> create(@Valid @RequestBody EventDefSaveReq req) {
        return R.ok(eventDefService.create(req));
    }

    @ApiOperation("修改埋点定义（语义变化自动升版本）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:data:edit')")
    @AdminOpLog(type = "EVENT_DEF_UPDATE", target = "EVENT_DEF", description = "修改埋点定义")
    public R<EventDef> update(@PathVariable Long id, @Valid @RequestBody EventDefSaveReq req) {
        return R.ok(eventDefService.update(id, req));
    }

    @ApiOperation("校验上报事件是否已登记、字段是否合法")
    @PostMapping("/validate")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<Map<String, Object>> validate(@RequestBody TrackEventReq req) {
        return R.ok(eventDefService.validate(req));
    }

    @ApiOperation("废弃埋点定义")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:data:edit')")
    @AdminOpLog(type = "EVENT_DEF_RETIRE", target = "EVENT_DEF", description = "废弃埋点定义")
    public R<Void> retire(@PathVariable Long id) {
        eventDefService.retire(id);
        return R.ok();
    }
}
