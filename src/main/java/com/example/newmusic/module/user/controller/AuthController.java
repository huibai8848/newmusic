package com.example.newmusic.module.user.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.module.user.dto.LoginReq;
import com.example.newmusic.module.user.dto.RefreshReq;
import com.example.newmusic.module.user.dto.RegisterReq;
import com.example.newmusic.module.user.service.AuthService;
import com.example.newmusic.module.user.service.UserService;
import com.example.newmusic.module.user.vo.LoginVO;
import com.example.newmusic.module.user.vo.UserVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@Api(tags = "C端接口-认证")
@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @ApiOperation(value = "注册", notes = "用户名 + 密码 + 昵称；注册成功后自动授予 ROLE_USER")
    @PostMapping("/register")
    @RateLimit(limit = 5, windowSeconds = 60, type = "auth",
            message = "注册过于频繁，请稍后再试")
    public R<UserVO> register(@RequestBody @Valid RegisterReq req, HttpServletRequest request) {
        return R.ok(userService.register(req, IpUtil.getClientIp(request)));
    }

    @ApiOperation(value = "登录", notes = "支持用户名或手机号；返回 accessToken(30min) + refreshToken(7d)")
    @PostMapping("/login")
    @RateLimit(limit = 30, windowSeconds = 60, type = "auth")
    public R<LoginVO> login(@RequestBody @Valid LoginReq req, HttpServletRequest request) {
        // 账号维度（10 次/分钟）在服务内单独限：标识来自请求体，注解拿不到（见 RateLimitDimension.ACCOUNT）
        return R.ok(authService.login(req, IpUtil.getClientIp(request)));
    }

    @ApiOperation(value = "刷新令牌", notes = "用 refreshToken 换取新的双 Token（轮换）；旧 refreshToken 立即失效")
    @PostMapping("/refresh")
    public R<LoginVO> refresh(@RequestBody @Valid RefreshReq req) {
        return R.ok(authService.refresh(req.getRefreshToken()));
    }

    @ApiOperation(value = "登出", notes = "吊销当前 accessToken（Redis 黑名单）并作废该用户全部 refreshToken")
    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request) {
        authService.logout(resolveAccessToken(request));
        return R.ok();
    }

    /** 取出原始 accessToken 交给服务层吊销：只有它带着 jti 与过期时间 */
    private String resolveAccessToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length()).trim();
        }
        return null;
    }
}
