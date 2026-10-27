package com.example.newmusic.module.social.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.R;
import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.module.social.dto.ShareReq;
import com.example.newmusic.module.social.service.ShareService;
import com.example.newmusic.module.social.vo.ShareVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.net.URI;

@Api(tags = "社交-分享")
@RestController
public class ShareController {

    private final ShareService shareService;

    public ShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    @ApiOperation(value = "生成分享短链", notes = "公开接口，游客也可生成；登录后记录分享人用于归因。")
    @PostMapping("/api/v1/share")
    @RateLimit(limit = 30, windowSeconds = 60, type = "social_share")
    public R<ShareVO> create(@Valid @RequestBody ShareReq req) {
        return R.ok(shareService.create(UserContext.getUserId(), req));
    }

    @ApiOperation(value = "短链跳转", notes = "302 到 C 端页面，并带 from=share&sharerId 归因参数。")
    @GetMapping("/s/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        Long userId = UserContext.getUserId();
        String visitor = userId == null ? IpUtil.getClientIp(request) : String.valueOf(userId);
        String url = shareService.resolve(code, visitor);
        if (url == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
}
