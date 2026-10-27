package com.example.newmusic.module.admin.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.admin.dto.AdminRoomQueryReq;
import com.example.newmusic.module.admin.dto.ForceCloseRoomReq;
import com.example.newmusic.module.admin.dto.RoomCreateBanReq;
import com.example.newmusic.module.admin.dto.RoomMessageQueryReq;
import com.example.newmusic.module.admin.service.AdminRoomService;
import com.example.newmusic.module.room.vo.RoomVO;
import com.example.newmusic.module.ws.entity.RoomMessage;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@Api(tags = "后台接口-房间管理")
@RestController
@RequestMapping("/api/v1/admin/rooms")
public class AdminRoomController {

    private final AdminRoomService roomService;

    public AdminRoomController(AdminRoomService roomService) {
        this.roomService = roomService;
    }

    @ApiOperation(value = "房间列表", notes = "含已关闭/已解散房间")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:room:list')")
    public R<PageResult<RoomVO>> list(AdminRoomQueryReq query) {
        return R.ok(roomService.page(query));
    }

    @ApiOperation("房间详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:room:list')")
    public R<RoomVO> detail(@PathVariable Long id) {
        return R.ok(roomService.detail(id));
    }

    @ApiOperation(value = "查看房间消息", notes = "敏感操作，每次查看都会留痕")
    @GetMapping("/{id}/messages")
    @PreAuthorize("hasAuthority('admin:room:message:view')")
    @AdminOpLog(type = "ROOM_MESSAGE_VIEW", target = "ROOM",
            description = "查看房间消息", sensitive = true)
    public R<PageResult<RoomMessage>> messages(@PathVariable Long id,
                                               RoomMessageQueryReq query) {
        query.setRoomId(id);
        return R.ok(roomService.messages(query));
    }

    @ApiOperation(value = "强制关闭房间", notes = "必须填写原因；房间成员立即断开连接")
    @PostMapping("/{id}/force-close")
    @PreAuthorize("hasAuthority('admin:room:close')")
    @AdminOpLog(type = "ROOM_FORCE_CLOSE", target = "ROOM",
            description = "强制关闭房间", sensitive = true)
    public R<Void> forceClose(@PathVariable Long id,
                              @RequestBody @Valid ForceCloseRoomReq req) {
        roomService.forceClose(id, req.getReason(), UserContext.requireUserId());
        return R.ok();
    }

    @ApiOperation(value = "禁止用户创建房间", notes = "hours=0 或留空表示永久；只限制开房，不影响其它功能")
    @PostMapping("/owners/{userId}/create-ban")
    @PreAuthorize("hasAuthority('admin:room:close')")
    @AdminOpLog(type = "ROOM_CREATE_BAN", target = "USER",
            description = "禁止用户创建房间", sensitive = true)
    public R<Void> banCreate(@PathVariable Long userId,
                             @RequestBody @Valid RoomCreateBanReq req) {
        roomService.banCreate(userId, req, UserContext.requireUserId());
        return R.ok();
    }

    @ApiOperation("解除创建房间限制")
    @DeleteMapping("/owners/{userId}/create-ban")
    @PreAuthorize("hasAuthority('admin:room:close')")
    @AdminOpLog(type = "ROOM_CREATE_UNBAN", target = "USER",
            description = "解除创建房间限制", sensitive = true)
    public R<Void> unbanCreate(@PathVariable Long userId) {
        roomService.unbanCreate(userId, UserContext.requireUserId());
        return R.ok();
    }
}
