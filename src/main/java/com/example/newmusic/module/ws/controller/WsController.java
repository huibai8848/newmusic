package com.example.newmusic.module.ws.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.module.ws.dto.NotificationPushReq;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.entity.RoomMessage;
import com.example.newmusic.module.ws.service.NotificationService;
import com.example.newmusic.module.ws.service.RoomMessageService;
import com.example.newmusic.module.ws.service.WsMetrics;
import com.example.newmusic.module.ws.service.WsPresenceService;
import com.example.newmusic.module.ws.support.WsNodeId;
import com.example.newmusic.module.ws.support.WsSessionRegistry;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 模块 04 的 HTTP 侧接口。
 *
 * <p>WS 负责实时性，HTTP 负责**不会被实时性牺牲掉的东西**：
 * 离线通知拉取（BR-13-028/037）、断线消息补偿、已读状态、运行指标。
 * 这些都不适合塞进 WS 协议——一次 HTTP 拉取天然有分页和游标，
 * 而在 WS 上实现分页拉取是在重新发明 HTTP。
 */
@Api(tags = "实时通信-WebSocket")
@RestController
@RequestMapping("/api/v1/ws")
public class WsController {

    private final WsMetrics metrics;
    private final WsSessionRegistry registry;
    private final NotificationService notificationService;
    private final RoomMessageService roomMessageService;
    private final WsPresenceService presence;
    private final WsNodeId nodeId;
    private final ThreadPoolTaskExecutor messageExecutor;

    public WsController(WsMetrics metrics, WsSessionRegistry registry,
                        NotificationService notificationService,
                        RoomMessageService roomMessageService,
                        WsPresenceService presence, WsNodeId nodeId,
                        @Qualifier("wsMessageExecutor") ThreadPoolTaskExecutor messageExecutor) {
        this.metrics = metrics;
        this.registry = registry;
        this.notificationService = notificationService;
        this.roomMessageService = roomMessageService;
        this.presence = presence;
        this.nodeId = nodeId;
        this.messageExecutor = messageExecutor;
    }

    @ApiOperation(value = "WS 运行指标", notes = "运营/管理员可见。指标名与 PRD FR-13-010 对应。")
    @GetMapping("/stats")
    @PreAuthorize("hasAnyAuthority('ROLE_OPERATOR', 'ROLE_ADMIN')")
    public R<Map<String, Object>> stats() {
        int pending = messageExecutor.getThreadPoolExecutor().getQueue().size();
        Map<String, Object> snapshot = metrics.snapshot(
                registry.totalConnections(), registry.totalRooms(), pending);
        snapshot.put("roomsDetail", registry.roomCounts());
        snapshot.put("ws_node_id", nodeId.getNodeId());
        return R.ok(snapshot);
    }

    // ------------------------------------------------------------------ 通知

    @ApiOperation(value = "我的通知列表", notes = "最新在前，分页。离线通知也在这里拉取（BR-13-028）。")
    @GetMapping("/notifications")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 60, windowSeconds = 60, type = "ws-notify")
    public R<PageResult<Notification>> notifications(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(notificationService.page(UserContext.requireUserId(), pageNum, pageSize));
    }

    @ApiOperation(value = "未读数", notes = "以 DB 为准，Redis 只是缓存（BR-13-031）。")
    @GetMapping("/notifications/unread")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 120, windowSeconds = 60, type = "ws-notify")
    public R<Map<String, Object>> unread() {
        long count = notificationService.unreadCount(UserContext.requireUserId());
        return R.ok(java.util.Collections.<String, Object>singletonMap("unread", count));
    }

    @ApiOperation(value = "标记单条已读", notes = "幂等；不属于自己的通知按不存在处理。")
    @PutMapping("/notifications/{id}/read")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 120, windowSeconds = 60, type = "ws-notify")
    public R<Void> read(@PathVariable Long id) {
        notificationService.markRead(UserContext.requireUserId(), id);
        return R.ok();
    }

    @ApiOperation(value = "全部标记已读")
    @PutMapping("/notifications/read-all")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "ws-notify")
    public R<Map<String, Object>> readAll() {
        int changed = notificationService.markAllRead(UserContext.requireUserId());
        return R.ok(java.util.Collections.<String, Object>singletonMap("changed", changed));
    }

    @ApiOperation(value = "管理端推送通知", notes = "先落库再推送给在线用户；离线用户通过列表接口拉取。")
    @PostMapping("/admin/notifications/push")
    @PreAuthorize("hasAnyAuthority('ROLE_OPERATOR', 'ROLE_ADMIN')")
    public R<Map<String, Object>> push(@Valid @RequestBody NotificationPushReq req) {
        List<Long> ids = notificationService.pushBatch(req.getUserIds(), req.getNotifyType(),
                req.getTitle(), req.getContent(), req.getTargetType(), req.getTargetId());
        return R.ok(java.util.Collections.<String, Object>singletonMap("created", ids.size()));
    }

    // ------------------------------------------------------------------ 房间补偿

    @ApiOperation(value = "房间消息补偿", notes = "按 messageId 游标向后拉取（BR-13-037）。"
            + "不依赖 WS，重连后用它补齐断线期间的消息。")
    @GetMapping("/rooms/{roomId}/messages")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 120, windowSeconds = 60, type = "ws-room")
    public R<List<RoomMessage>> messages(
            @ApiParam(value = "房间ID", required = true) @PathVariable Long roomId,
            @RequestParam(defaultValue = "0") long sinceId,
            @RequestParam(defaultValue = "50") int limit) {
        UserContext.requireUserId();
        return R.ok(roomMessageService.listSince(roomId, sinceId, limit));
    }

    @ApiOperation(value = "房间权威播放态", notes = "给新加入者/重连者做首屏对齐；与 room.sync 内容一致。")
    @GetMapping("/rooms/{roomId}/playstate")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 120, windowSeconds = 60, type = "ws-room")
    public R<Map<String, Object>> playState(@PathVariable Long roomId) {
        UserContext.requireUserId();
        return R.ok(presence.playSnapshot(roomId));
    }
}
