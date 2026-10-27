package com.example.newmusic.module.socialupgrade.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.social.dto.NotificationReadReq;
import com.example.newmusic.module.socialupgrade.service.MessageCenterService;
import com.example.newmusic.module.socialupgrade.support.NotificationCategory;
import com.example.newmusic.module.socialupgrade.vo.UnreadVO;
import com.example.newmusic.module.ws.entity.Notification;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@Api(tags = "社交升级-消息中心")
@RestController
@RequestMapping("/api/v1/messages")
public class MessageCenterController {

    private final MessageCenterService messageCenterService;

    public MessageCenterController(MessageCenterService messageCenterService) {
        this.messageCenterService = messageCenterService;
    }

    @ApiOperation("互动消息")
    @GetMapping("/interactions")
    public R<PageResult<Notification>> interactions(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return page(NotificationCategory.INTERACTION, page, size);
    }

    @ApiOperation("新增粉丝")
    @GetMapping("/followers")
    public R<PageResult<Notification>> followers(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return page(NotificationCategory.FOLLOW, page, size);
    }

    @ApiOperation("系统通知")
    @GetMapping("/system")
    public R<PageResult<Notification>> system(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return page(NotificationCategory.SYSTEM, page, size);
    }

    @ApiOperation("音乐提醒")
    @GetMapping("/music")
    public R<PageResult<Notification>> music(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return page(NotificationCategory.MUSIC, page, size);
    }

    @ApiOperation("五分类未读数")
    @GetMapping("/unread")
    public R<UnreadVO> unread() {
        return R.ok(messageCenterService.unread(UserContext.requireUserId()));
    }

    @ApiOperation("标记指定消息已读")
    @PostMapping("/read")
    public R<Integer> read(@Valid @RequestBody NotificationReadReq req) {
        return R.ok(messageCenterService.markRead(UserContext.requireUserId(), req.getIds()));
    }

    @ApiOperation(value = "全部标记已读", notes = "category 为空表示全部；支持 interaction/follow/message/system/music")
    @PostMapping("/read-all")
    public R<Integer> readAll(@RequestParam(required = false) String category) {
        return R.ok(messageCenterService.markAllRead(UserContext.requireUserId(), category));
    }

    private R<PageResult<Notification>> page(String category, int page, int size) {
        return R.ok(messageCenterService.page(UserContext.requireUserId(), category, page, size));
    }
}
