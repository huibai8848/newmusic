package com.example.newmusic.module.admin.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.admin.dto.AdminAccountSaveReq;
import com.example.newmusic.module.admin.dto.AdminAccountStatusReq;
import com.example.newmusic.module.admin.entity.AdminRole;
import com.example.newmusic.module.admin.service.AdminAccountService;
import com.example.newmusic.module.admin.vo.AdminUserVO;
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

/**
 * 管理员账号管理。整组接口只授予 {@code admin:adminuser:manage}（仅超管）。
 */
@Api(tags = "后台接口-管理员账号")
@RestController
@RequestMapping("/api/v1/admin/accounts")
@PreAuthorize("hasAuthority('admin:adminuser:manage')")
public class AdminAccountController {

    private final AdminAccountService accountService;

    public AdminAccountController(AdminAccountService accountService) {
        this.accountService = accountService;
    }

    @ApiOperation("管理员列表")
    @GetMapping
    public R<PageResult<AdminUserVO>> page(PageQuery query,
                                           @RequestParam(required = false) Integer status) {
        return R.ok(accountService.page(query, status));
    }

    @ApiOperation(value = "角色列表", notes = "SUPER_ADMIN / ADMIN / OPERATOR")
    @GetMapping("/roles")
    public R<List<AdminRole>> roles() {
        return R.ok(accountService.roles());
    }

    @ApiOperation("管理员详情")
    @GetMapping("/{id}")
    public R<AdminUserVO> detail(@PathVariable Long id) {
        return R.ok(accountService.toVO(accountService.requireById(id), true));
    }

    @ApiOperation(value = "新增管理员", notes = "初始密码只能使用一次，首次登录强制改密")
    @PostMapping
    @AdminOpLog(type = "ADMIN_CREATE", target = "ADMIN_USER", description = "新增管理员", sensitive = true)
    public R<Long> create(@RequestBody @Valid AdminAccountSaveReq req) {
        return R.ok(accountService.create(req, UserContext.requireUserId()));
    }

    @ApiOperation(value = "编辑管理员", notes = "密码留空表示不修改；角色变更会使该账号当前会话立即失效")
    @PutMapping("/{id}")
    @AdminOpLog(type = "ADMIN_UPDATE", target = "ADMIN_USER", description = "编辑管理员", sensitive = true)
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid AdminAccountSaveReq req) {
        accountService.update(id, req, UserContext.requireUserId());
        return R.ok();
    }

    @ApiOperation(value = "启用/禁用管理员", notes = "不能禁用自己；系统保留至少一个可用超管")
    @PostMapping("/{id}/status")
    @AdminOpLog(type = "ADMIN_STATUS", target = "ADMIN_USER", description = "启用/禁用管理员", sensitive = true)
    public R<Void> changeStatus(@PathVariable Long id, @RequestBody @Valid AdminAccountStatusReq req) {
        accountService.changeStatus(id, req.getStatus(), UserContext.requireUserId());
        return R.ok();
    }

    @ApiOperation(value = "重置管理员密码", notes = "返回一次性临时密码；该账号所有会话立即失效")
    @PostMapping("/{id}/reset-password")
    @AdminOpLog(type = "ADMIN_RESET_PASSWORD", target = "ADMIN_USER",
            description = "重置管理员密码", sensitive = true)
    public R<String> resetPassword(@PathVariable Long id) {
        return R.ok(accountService.resetPassword(id, UserContext.requireUserId()));
    }

    @ApiOperation(value = "重置管理员 TOTP", notes = "返回新密钥；需在验证器中重新绑定")
    @PostMapping("/{id}/reset-totp")
    @AdminOpLog(type = "ADMIN_RESET_TOTP", target = "ADMIN_USER",
            description = "重置管理员 TOTP", sensitive = true)
    public R<String> resetTotp(@PathVariable Long id) {
        return R.ok(accountService.resetTotp(id, UserContext.requireUserId()));
    }
}
