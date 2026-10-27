package com.example.newmusic.module.admin.controller;

import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.admin.dto.SysConfigSaveReq;
import com.example.newmusic.module.admin.dto.SysConfigUpdateReq;
import com.example.newmusic.module.admin.entity.SysConfig;
import com.example.newmusic.module.admin.entity.SysConfigHistory;
import com.example.newmusic.module.admin.service.SysConfigService;
import com.example.newmusic.module.ops.service.OpsConfigService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.util.Map;

/**
 * 系统配置管理。
 *
 * <p>保存密钥类配置（键名含 password/secret/token…）会被 80026 拒绝——
 * 系统配置会进历史、会被导出，不是密钥仓库。
 */
@Api(tags = "后台接口-系统配置")
@RestController
@RequestMapping("/api/v1/admin/configs")
public class AdminConfigController {

    private final SysConfigService configService;
    private final OpsConfigService opsConfigService;

    public AdminConfigController(SysConfigService configService, OpsConfigService opsConfigService) {
        this.configService = configService;
        this.opsConfigService = opsConfigService;
    }

    @ApiOperation("配置列表")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:sysconfig:view')")
    public R<PageResult<SysConfig>> page(PageQuery query,
                                         @RequestParam(required = false) String group) {
        return R.ok(configService.page(query, group));
    }

    @ApiOperation(value = "新增配置", notes = "配置键不可重复；值按 valueType 校验")
    @PostMapping
    @PreAuthorize("hasAuthority('admin:sysconfig:edit')")
    @AdminOpLog(type = "SYSCONFIG_CREATE", target = "SYS_CONFIG", description = "新增系统配置", sensitive = true)
    public R<Map<String, Long>> create(@RequestBody @Valid SysConfigSaveReq req) {
        return R.ok(java.util.Collections.singletonMap("id", configService.create(req)));
    }

    @ApiOperation(value = "修改配置值", notes = "idOrKey 可传自增 ID 或配置键；写变更历史")
    @PutMapping("/{idOrKey}")
    @PreAuthorize("hasAuthority('admin:sysconfig:edit')")
    @AdminOpLog(type = "SYSCONFIG_UPDATE", target = "SYS_CONFIG", description = "修改系统配置", sensitive = true)
    public R<Void> update(@PathVariable String idOrKey, @RequestBody @Valid SysConfigUpdateReq req) {
        // 模块19的配置中心按语义键操作（ops.degrade.level），老后台仍按 ID；同一个 URL 兼容两种
        if (idOrKey.matches("\\d+")) {
            configService.update(Long.valueOf(idOrKey), req);
        } else {
            configService.updateByKey(idOrKey, req);
        }
        // 运维配置有自己的本地缓存：后台走的是这条通用路径时也要让它立即失效，
        // 否则回滚 ops.* 配置后会读到旧值，直到下一次 5 秒刷新
        opsConfigService.invalidate();
        return R.ok();
    }

    @ApiOperation("配置变更历史（最近 100 条）")
    @GetMapping("/{idOrKey}/history")
    @PreAuthorize("hasAuthority('admin:sysconfig:view')")
    public R<List<SysConfigHistory>> history(@PathVariable String idOrKey) {
        if (idOrKey.matches("\\d+")) {
            return R.ok(configService.historyOfConfig(Long.valueOf(idOrKey)));
        }
        return R.ok(configService.historyOfKey(idOrKey));
    }

    @ApiOperation(value = "回滚到指定历史值", notes = "historyId 为变更历史记录 ID；回滚本身也会写一条历史")
    @PostMapping("/history/{historyId}/rollback")
    @PreAuthorize("hasAuthority('admin:sysconfig:edit')")
    @AdminOpLog(type = "SYSCONFIG_ROLLBACK", target = "SYS_CONFIG", description = "回滚系统配置", sensitive = true)
    public R<Void> rollback(@PathVariable Long historyId) {
        configService.rollback(historyId);
        opsConfigService.invalidate();
        return R.ok();
    }
}
