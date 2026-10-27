package com.example.newmusic.module.dataplatform.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.dataplatform.service.ProfileTagService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 用户画像标签（FR-26-004）。 */
@Api(tags = "用户画像")
@RestController
@RequestMapping("/api/v1")
public class ProfileTagController {

    private final ProfileTagService profileTagService;

    public ProfileTagController(ProfileTagService profileTagService) {
        this.profileTagService = profileTagService;
    }

    @ApiOperation("我自己的画像标签（BR-26-046 透明度）")
    @GetMapping("/users/me/profile-tags")
    public R<Map<String, Object>> mine(@RequestParam(defaultValue = "false") boolean refresh) {
        return R.ok(profileTagService.view(UserContext.requireUserId(), refresh));
    }

    @ApiOperation("查看指定用户的画像（管理端）")
    @GetMapping("/admin/users/{userId}/profile-tags")
    @PreAuthorize("hasAuthority('admin:user:view')")
    public R<Map<String, Object>> adminView(@PathVariable Long userId,
                                            @RequestParam(required = false) String date) {
        return R.ok(profileTagService.adminView(userId, date));
    }

    @ApiOperation("标签目录（10 类）")
    @GetMapping("/admin/profile-tags/catalog")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<List<Map<String, String>>> catalog() {
        return R.ok(ProfileTagService.CATALOG);
    }

    @ApiOperation("重算画像标签（单用户或全量）")
    @PostMapping("/admin/profile-tags/recompute")
    @PreAuthorize("hasAuthority('admin:data:edit')")
    @AdminOpLog(type = "PROFILE_RECOMPUTE", target = "USER_PROFILE_TAG", description = "重算用户画像")
    public R<Map<String, Object>> recompute(@RequestParam(required = false) Long userId,
                                            @RequestParam(required = false) String date) {
        if (userId != null) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("userId", String.valueOf(userId));
            result.put("tags", profileTagService.recomputeUser(userId, date));
            return R.ok(result);
        }
        return R.ok(profileTagService.recomputeAll(date));
    }
}
