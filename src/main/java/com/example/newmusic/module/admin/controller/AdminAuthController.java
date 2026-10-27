package com.example.newmusic.module.admin.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.dto.AdminLoginReq;
import com.example.newmusic.module.admin.dto.AdminPasswordChangeReq;
import com.example.newmusic.module.admin.security.AdminCookieSupport;
import com.example.newmusic.module.admin.service.AdminAuthService;
import com.example.newmusic.module.admin.support.AdminCaptchaService;
import com.example.newmusic.module.admin.vo.AdminLoginVO;
import com.example.newmusic.module.admin.vo.AdminUserVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

/**
 * 后台认证接口。Token 只通过 httpOnly Cookie 下发，响应体不含任何 Token 字段。
 */
@Api(tags = "后台接口-认证")
@RestController
@RequestMapping("/api/v1/admin/auth")
public class AdminAuthController {

    private static final String BEARER = "Bearer ";

    private final AdminAuthService authService;
    private final AdminCookieSupport cookieSupport;

    public AdminAuthController(AdminAuthService authService, AdminCookieSupport cookieSupport) {
        this.authService = authService;
        this.cookieSupport = cookieSupport;
    }

    @ApiOperation(value = "图形验证码", notes = "PNG data URI；验证码 2 分钟有效且一次性")
    @GetMapping("/captcha")
    public R<AdminCaptchaService.Captcha> captcha() {
        return R.ok(authService.captcha());
    }

    @ApiOperation(value = "登录", notes = "验证码 + 密码 + IP 白名单 + TOTP；连续失败 5 次锁定 30 分钟")
    @PostMapping("/login")
    public R<AdminLoginVO> login(@RequestBody @Valid AdminLoginReq req,
                                 HttpServletRequest request,
                                 HttpServletResponse response) {
        return R.ok(authService.login(req, request, response));
    }

    @ApiOperation(value = "刷新 Token", notes = "refresh 一次性轮换；重放会使该账号所有会话失效")
    @PostMapping("/refresh")
    public R<AdminLoginVO> refresh(HttpServletRequest request, HttpServletResponse response) {
        return R.ok(authService.refresh(request, response));
    }

    @ApiOperation(value = "退出登录", notes = "递增会话版本，所有设备立即失效")
    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                          HttpServletRequest request,
                          HttpServletResponse response) {
        String token = resolveAccess(authorization, request);
        authService.logout(token, response);
        return R.ok();
    }

    @ApiOperation(value = "当前管理员", notes = "返回最新的角色与权限码（超管已展开为具体权限）")
    @GetMapping("/me")
    public R<AdminUserVO> me() {
        return R.ok(authService.me());
    }

    @ApiOperation(value = "修改密码", notes = "原密码校验；成功后重新签发 Token")
    @PostMapping("/password")
    public R<AdminLoginVO> changePassword(@RequestBody @Valid AdminPasswordChangeReq req,
                                          HttpServletRequest request,
                                          HttpServletResponse response) {
        return R.ok(authService.changePassword(req, request, response));
    }

    private String resolveAccess(String authorization, HttpServletRequest request) {
        if (StringUtils.hasText(authorization) && authorization.startsWith(BEARER)) {
            return authorization.substring(BEARER.length()).trim();
        }
        return cookieSupport.readAccess(request);
    }
}
