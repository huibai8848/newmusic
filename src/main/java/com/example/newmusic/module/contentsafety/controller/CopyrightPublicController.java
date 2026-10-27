package com.example.newmusic.module.contentsafety.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.module.contentsafety.dto.ComplaintCreateReq;
import com.example.newmusic.module.contentsafety.entity.CopyrightComplaint;
import com.example.newmusic.module.contentsafety.service.CopyrightService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 侵权投诉登记入口。
 *
 * <p>本期要求登录后提交（便于留痕与反滥用）；若将来对站外权利人开放，
 * 需要在 SecurityConfig 单独放行并加图形验证码，不要把整个 /copyright 放开。
 */
@Api(tags = "版权侵权投诉")
@RestController
@RequestMapping("/api/v1/copyright")
public class CopyrightPublicController {

    private final CopyrightService copyrightService;

    public CopyrightPublicController(CopyrightService copyrightService) {
        this.copyrightService = copyrightService;
    }

    @ApiOperation("登记侵权投诉（需勾选真实性声明）")
    @PostMapping("/complaints")
    public R<CopyrightComplaint> create(@RequestBody @Valid ComplaintCreateReq req) {
        return R.ok(copyrightService.createComplaint(req));
    }
}
