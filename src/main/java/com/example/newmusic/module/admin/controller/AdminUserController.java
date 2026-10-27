package com.example.newmusic.module.admin.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.admin.dto.AdminUserEditReq;
import com.example.newmusic.module.admin.dto.AdminUserQueryReq;
import com.example.newmusic.module.admin.dto.BanReq;
import com.example.newmusic.module.admin.service.AdminUserManageService;
import com.example.newmusic.module.admin.vo.AdminCUserVO;
import com.example.newmusic.module.admin.vo.SensitivePhoneVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Collections;
import java.util.List;

@Api(tags = "后台接口-用户管理")
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserManageService userManageService;

    public AdminUserController(AdminUserManageService userManageService) {
        this.userManageService = userManageService;
    }

    @ApiOperation(value = "用户列表", notes = "支持状态/类型/是否封禁筛选")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:user:list')")
    public R<PageResult<AdminCUserVO>> page(AdminUserQueryReq query) {
        return R.ok(userManageService.page(query));
    }

    @ApiOperation("用户详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:user:view')")
    public R<AdminCUserVO> detail(@PathVariable Long id) {
        return R.ok(userManageService.detail(id));
    }

    @ApiOperation(value = "编辑用户", notes = "昵称全局唯一；用户类型仅允许 1/2")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:user:edit')")
    @AdminOpLog(type = "USER_EDIT", target = "USER", description = "编辑用户资料")
    public R<AdminCUserVO> edit(@PathVariable Long id, @RequestBody @Valid AdminUserEditReq req) {
        return R.ok(userManageService.edit(id, req, UserContext.requireUserId()));
    }

    @ApiOperation(value = "查看完整手机号", notes = "敏感权限；每次调用都会写操作日志")
    @GetMapping("/{id}/phone")
    @PreAuthorize("hasAuthority('admin:user:phone:view')")
    @AdminOpLog(type = "USER_PHONE_VIEW", target = "USER", description = "查看用户完整手机号", sensitive = true)
    public R<SensitivePhoneVO> phone(@PathVariable Long id) {
        return R.ok(userManageService.phone(id));
    }

    @ApiOperation(value = "封禁单个用户", notes = "原因 5-500 字；为空 banUntil 表示永久")
    @PostMapping("/{id}/ban")
    @PreAuthorize("hasAuthority('admin:user:ban')")
    @AdminOpLog(type = "USER_BAN", target = "USER", description = "封禁用户", sensitive = true)
    public R<Integer> ban(@PathVariable Long id, @RequestBody @Valid BanReq req) {
        return R.ok(userManageService.ban(Collections.singletonList(id), req,
                UserContext.requireUserId()));
    }

    @ApiOperation(value = "批量封禁", notes = "单次最多 100 个用户")
    @PostMapping("/ban")
    @PreAuthorize("hasAuthority('admin:user:ban')")
    @AdminOpLog(type = "USER_BAN_BATCH", target = "USER", description = "批量封禁用户", sensitive = true)
    public R<Integer> batchBan(@RequestBody @Valid BanReq req) {
        List<Long> ids = req.getUserIds();
        return R.ok(userManageService.ban(ids, req, UserContext.requireUserId()));
    }

    @ApiOperation(value = "解封用户", notes = "同时清除 DB 与 Redis 封禁标记")
    @PostMapping("/{id}/unban")
    @PreAuthorize("hasAuthority('admin:user:unban')")
    @AdminOpLog(type = "USER_UNBAN", target = "USER", description = "解封用户", sensitive = true)
    public R<Void> unban(@PathVariable Long id) {
        userManageService.unban(id, UserContext.requireUserId());
        return R.ok();
    }

    @ApiOperation(value = "重置用户密码", notes = "返回一次性临时密码，并强制下次登录改密")
    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('admin:user:edit')")
    @AdminOpLog(type = "USER_RESET_PASSWORD", target = "USER", description = "重置用户密码", sensitive = true)
    public R<String> resetPassword(@PathVariable Long id) {
        return R.ok(userManageService.resetPassword(id, UserContext.requireUserId()));
    }
}
