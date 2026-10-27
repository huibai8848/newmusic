package com.example.newmusic.module.contentsafety.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.contentsafety.dto.CreditAdjustReq;
import com.example.newmusic.module.contentsafety.dto.MinorConfigReq;
import com.example.newmusic.module.contentsafety.dto.PermanentBanReq;
import com.example.newmusic.module.contentsafety.dto.RestrictReq;
import com.example.newmusic.module.contentsafety.entity.CreditLog;
import com.example.newmusic.module.contentsafety.entity.MinorProtection;
import com.example.newmusic.module.contentsafety.entity.UserCredit;
import com.example.newmusic.module.contentsafety.entity.UserRestriction;
import com.example.newmusic.module.contentsafety.service.MinorProtectionService;
import com.example.newmusic.module.contentsafety.service.UserRestrictionService;
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
import java.util.List;
import java.util.Map;

/**
 * 用户处置、信用分与未成年人保护配置（FR-27-007/009）。
 */
@Api(tags = "后台接口-用户安全处置")
@RestController
@RequestMapping("/api/v1/admin/users")
public class UserSafetyAdminController {

    private final UserRestrictionService restrictionService;
    private final MinorProtectionService minorService;

    public UserSafetyAdminController(UserRestrictionService restrictionService,
                                     MinorProtectionService minorService) {
        this.restrictionService = restrictionService;
        this.minorService = minorService;
    }

    @ApiOperation("限制用户（警告/禁言/封禁/限制上传）")
    @PostMapping("/{id}/restrict")
    @PreAuthorize("hasAuthority('admin:user:restrict')")
    @AdminOpLog(type = "USER_RESTRICT", target = "USER", description = "限制用户")
    public R<UserRestriction> restrict(@PathVariable Long id,
                                       @RequestBody @Valid RestrictReq req) {
        return R.ok(restrictionService.restrict(id, req));
    }

    @ApiOperation("解除限制（仅超管）")
    @DeleteMapping("/{id}/restrict/{rid}")
    @PreAuthorize("hasAuthority('admin:user:restrict')")
    @AdminOpLog(type = "USER_RESTRICT_RELEASE", target = "USER", description = "解除用户限制")
    public R<UserRestriction> release(@PathVariable Long id, @PathVariable Long rid,
                                      @RequestParam(required = false) String reason) {
        return R.ok(restrictionService.release(id, rid, reason));
    }

    @ApiOperation("生效中的限制")
    @GetMapping("/{id}/restrictions")
    @PreAuthorize("hasAuthority('admin:user:view')")
    public R<List<UserRestriction>> active(@PathVariable Long id) {
        return R.ok(restrictionService.active(id));
    }

    @ApiOperation("全部处置记录")
    @GetMapping("/{id}/restrictions/history")
    @PreAuthorize("hasAuthority('admin:user:view')")
    public R<List<UserRestriction>> history(@PathVariable Long id) {
        return R.ok(restrictionService.history(id));
    }

    @ApiOperation("永久封禁（超管 + confirm=BAN）")
    @PostMapping("/{id}/ban-permanent")
    @PreAuthorize("hasAuthority('admin:user:ban:permanent')")
    @AdminOpLog(type = "USER_BAN_PERMANENT", target = "USER", description = "永久封禁")
    public R<UserRestriction> permanentBan(@PathVariable Long id,
                                           @RequestBody @Valid PermanentBanReq req) {
        return R.ok(restrictionService.permanentBan(id, req));
    }

    @ApiOperation("信用分")
    @GetMapping("/{id}/credit")
    @PreAuthorize("hasAuthority('admin:user:view')")
    public R<UserCredit> credit(@PathVariable Long id) {
        return R.ok(restrictionService.credit(id));
    }

    @ApiOperation("信用分变更记录")
    @GetMapping("/{id}/credit/logs")
    @PreAuthorize("hasAuthority('admin:user:view')")
    public R<List<CreditLog>> creditLogs(@PathVariable Long id) {
        return R.ok(restrictionService.creditLogs(id));
    }

    @ApiOperation("调整信用分")
    @PostMapping("/{id}/credit/adjust")
    @PreAuthorize("hasAuthority('admin:user:credit:edit')")
    @AdminOpLog(type = "USER_CREDIT_ADJUST", target = "USER", description = "调整信用分")
    public R<UserCredit> adjustCredit(@PathVariable Long id,
                                      @RequestBody @Valid CreditAdjustReq req) {
        return R.ok(restrictionService.adjustCredit(id, req.getChangeScore(),
                req.getReason(), "ADMIN", null));
    }

    @ApiOperation("未成年人保护配置")
    @GetMapping("/{id}/minor-protection")
    @PreAuthorize("hasAuthority('admin:user:view')")
    public R<MinorProtection> minorConfig(@PathVariable Long id) {
        return R.ok(minorService.config(id));
    }

    @ApiOperation("保存未成年人保护配置（未成年人强制青少年模式）")
    @PutMapping("/{id}/minor-protection")
    @PreAuthorize("hasAuthority('admin:user:edit')")
    @AdminOpLog(type = "USER_MINOR_CONFIG", target = "USER", description = "未成年人保护配置")
    public R<MinorProtection> saveMinorConfig(@PathVariable Long id,
                                              @RequestBody @Valid MinorConfigReq req) {
        return R.ok(minorService.saveConfig(id, req));
    }

    @ApiOperation("绑定监护人")
    @PostMapping("/{id}/minor-protection/guardian")
    @PreAuthorize("hasAuthority('admin:user:edit')")
    @AdminOpLog(type = "USER_MINOR_GUARDIAN", target = "USER", description = "绑定监护人")
    public R<MinorProtection> bindGuardian(@PathVariable Long id,
                                           @RequestParam Long guardianUserId) {
        return R.ok(minorService.bindGuardian(id, guardianUserId));
    }

    @ApiOperation("监护人名下的未成年人")
    @GetMapping("/{id}/wards")
    @PreAuthorize("hasAuthority('admin:user:view')")
    public R<List<Map<String, Object>>> wards(@PathVariable Long id) {
        return R.ok(minorService.wards(id));
    }
}
