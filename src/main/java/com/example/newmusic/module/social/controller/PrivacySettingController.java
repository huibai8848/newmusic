package com.example.newmusic.module.social.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.social.dto.PrivacySettingReq;
import com.example.newmusic.module.social.entity.PrivacySetting;
import com.example.newmusic.module.social.service.PrivacySettingService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@Api(tags = "社交-隐私设置")
@RestController
@RequestMapping("/api/v1/privacy-settings")
public class PrivacySettingController {

    private final PrivacySettingService privacySettingService;

    public PrivacySettingController(PrivacySettingService privacySettingService) {
        this.privacySettingService = privacySettingService;
    }

    @ApiOperation("读取隐私设置")
    @GetMapping
    public R<PrivacySetting> get() {
        return R.ok(privacySettingService.get(UserContext.requireUserId()));
    }

    @ApiOperation("更新隐私设置")
    @PutMapping
    public R<PrivacySetting> update(@Valid @RequestBody PrivacySettingReq req) {
        return R.ok(privacySettingService.update(UserContext.requireUserId(), req));
    }
}
