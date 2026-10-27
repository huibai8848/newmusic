package com.example.newmusic.module.social.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.social.dto.NotificationReadReq;
import com.example.newmusic.module.social.dto.NotificationSettingReq;
import com.example.newmusic.module.social.entity.NotificationSetting;
import com.example.newmusic.module.social.service.NotificationCenterService;
import com.example.newmusic.module.social.service.NotificationSettingService;
import com.example.newmusic.module.ws.entity.Notification;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@Api(tags = "社交-通知中心")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationCenterController {

    private final NotificationCenterService notificationCenterService;
    private final NotificationSettingService settingService;

    public NotificationCenterController(NotificationCenterService notificationCenterService,
                                        NotificationSettingService settingService) {
        this.notificationCenterService = notificationCenterService;
        this.settingService = settingService;
    }

    @ApiOperation(value = "通知列表", notes = "tab：all/interaction/follow/message/system。")
    @GetMapping
    public R<PageResult<Notification>> list(@RequestParam(defaultValue = "all") String tab,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return R.ok(notificationCenterService.page(UserContext.requireUserId(), tab, page, size));
    }

    @ApiOperation("未读数")
    @GetMapping("/unread-count")
    public R<Long> unreadCount() {
        return R.ok(notificationCenterService.unread(UserContext.requireUserId()));
    }

    @ApiOperation("标记已读")
    @PutMapping("/read")
    public R<Integer> read(@Valid @RequestBody NotificationReadReq req) {
        return R.ok(notificationCenterService.markReadBatch(
                UserContext.requireUserId(), req.getIds()));
    }

    @ApiOperation("全部已读")
    @PutMapping("/read-all")
    public R<Integer> readAll() {
        return R.ok(notificationCenterService.markAllRead(UserContext.requireUserId()));
    }

    @ApiOperation("删除单条通知")
    @DeleteMapping("/{id}")
    public R<Boolean> delete(@PathVariable Long id) {
        return R.ok(notificationCenterService.delete(UserContext.requireUserId(), id));
    }

    @ApiOperation("清空通知")
    @DeleteMapping
    public R<Integer> clear() {
        return R.ok(notificationCenterService.clearAll(UserContext.requireUserId()));
    }

    @ApiOperation("通知设置")
    @GetMapping("/settings")
    public R<NotificationSetting> getSettings() {
        return R.ok(settingService.get(UserContext.requireUserId()));
    }

    @ApiOperation("更新通知设置")
    @PutMapping("/settings")
    public R<NotificationSetting> updateSettings(@Valid @RequestBody NotificationSettingReq req) {
        return R.ok(settingService.update(UserContext.requireUserId(), req));
    }
}
