package com.example.newmusic.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.admin.dto.AdminRoomQueryReq;
import com.example.newmusic.module.admin.dto.RoomCreateBanReq;
import com.example.newmusic.module.admin.dto.RoomMessageQueryReq;
import com.example.newmusic.module.room.entity.Room;
import com.example.newmusic.module.room.mapper.RoomMapper;
import com.example.newmusic.module.room.service.RoomAssembler;
import com.example.newmusic.module.room.service.RoomNotifier;
import com.example.newmusic.module.room.service.RoomService;
import com.example.newmusic.module.room.service.RoomStateService;
import com.example.newmusic.module.room.vo.RoomVO;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.entity.RoomMessage;
import com.example.newmusic.module.ws.mapper.RoomMessageMapper;
import com.example.newmusic.module.ws.service.NotificationService;
import com.example.newmusic.module.ws.support.WsSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台房间管理：跨状态查看、强制关闭、创建限制、消息查看。
 *
 * <p>强制关闭复用与房主关房相同的顺序「广播 → 落库 → 断连」，只是发起人不同；
 * 消息查看是敏感操作，由 Controller 上的 {@code @AdminOpLog} 留痕。
 */
@Slf4j
@Service
public class AdminRoomService {

    private static final Duration PERMANENT_CREATE_BAN = Duration.ofDays(36500);

    private final RoomMapper roomMapper;
    private final RoomAssembler assembler;
    private final RoomService roomService;
    private final RoomStateService roomStateService;
    private final RoomNotifier notifier;
    private final WsSessionRegistry registry;
    private final RoomMessageMapper roomMessageMapper;
    private final NotificationService notificationService;

    public AdminRoomService(RoomMapper roomMapper,
                            RoomAssembler assembler,
                            RoomService roomService,
                            RoomStateService roomStateService,
                            RoomNotifier notifier,
                            WsSessionRegistry registry,
                            RoomMessageMapper roomMessageMapper,
                            NotificationService notificationService) {
        this.roomMapper = roomMapper;
        this.assembler = assembler;
        this.roomService = roomService;
        this.roomStateService = roomStateService;
        this.notifier = notifier;
        this.registry = registry;
        this.roomMessageMapper = roomMessageMapper;
        this.notificationService = notificationService;
    }

    public PageResult<RoomVO> page(AdminRoomQueryReq query) {
        query.normalize();
        QueryWrapper<Room> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(q -> q.like("name", query.getKeyword())
                    .or().like("room_no", query.getKeyword()));
        }
        if (query.getStatus() != null) {
            wrapper.eq("status", query.getStatus());
        }
        if (query.getRoomType() != null) {
            wrapper.eq("room_type", query.getRoomType());
        }
        if (query.getOwnerId() != null) {
            wrapper.eq("owner_id", query.getOwnerId());
        }
        wrapper.orderByDesc("create_time");
        Page<Room> page = roomMapper.selectPage(query.toPage(), wrapper);
        List<RoomVO> list = assembler.toVOs(page.getRecords());

        PageResult<RoomVO> result = new PageResult<>();
        result.setList(list);
        result.setTotal(page.getTotal());
        result.setPageNum(page.getCurrent());
        result.setPageSize(page.getSize());
        result.setPages(page.getPages());
        result.setHasNext(page.getCurrent() < page.getPages());
        result.setTruncated(query.isTruncated());
        return result;
    }

    public RoomVO detail(Long roomId) {
        return assembler.toVO(roomService.requireRoom(roomId));
    }

    public PageResult<RoomMessage> messages(RoomMessageQueryReq query) {
        if (query.getRoomId() == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "请选择房间");
        }
        query.normalize();
        QueryWrapper<RoomMessage> wrapper = new QueryWrapper<>();
        wrapper.eq("room_id", query.getRoomId());
        if (query.getMessageType() != null) {
            wrapper.eq("message_type", query.getMessageType());
        }
        if (query.getAuditStatus() != null) {
            wrapper.eq("audit_status", query.getAuditStatus());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like("content", query.getKeyword());
        }
        wrapper.orderByDesc("create_time").orderByDesc("id");
        return PageResult.of(roomMessageMapper.selectPage(query.toPage(), wrapper),
                query.isTruncated());
    }

    /** 强制关闭：仅活跃房间；原因写入系统消息与房主站内信。 */
    @Transactional
    public void forceClose(Long roomId, String reason, Long operatorId) {
        Room room = roomService.requireRoom(roomId);
        if (!Integer.valueOf(Room.STATUS_ACTIVE).equals(room.getStatus())) {
            throw new BizException(ResultCode.ROOM_STATUS_ABNORMAL, "只有活跃房间可以强制关闭");
        }
        notifier.system(roomId, null, "房间已被管理员关闭：" + reason);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roomId", String.valueOf(roomId));
        data.put("status", "force_closed");
        data.put("reason", reason);
        data.put("operatorId", String.valueOf(operatorId));
        notifier.roomEvent(roomId, "room.closed", data);

        room.setStatus(Room.STATUS_CLOSED);
        room.setOnlineCount(0);
        room.setLastActiveTime(LocalDateTime.now());
        roomMapper.updateById(room);
        registry.closeRoomSessions(roomId, new CloseStatus(4006, "room force closed"));
        notifyOwner(room.getOwnerId(), "房间被强制关闭",
                "您的房间「" + room.getName() + "」已被管理员关闭，原因：" + reason);
        log.warn("管理员强制关闭房间 roomId={} operatorId={} reason={}", roomId, operatorId, reason);
    }

    /** 禁止/解除禁止创建房间。hours=0 或空表示永久。 */
    public void banCreate(Long userId, RoomCreateBanReq req, Long operatorId) {
        Duration ttl = PERMANENT_CREATE_BAN;
        if (req.getHours() != null && req.getHours() > 0) {
            ttl = Duration.ofHours(req.getHours());
        }
        roomStateService.banCreate(userId, req.getReason(), ttl);
        notifyOwner(userId, "创建房间受限",
                "您已被限制创建房间，原因：" + req.getReason()
                        + (req.getHours() == null || req.getHours() <= 0 ? "（永久）" : "（" + req.getHours() + " 小时）"));
        log.warn("管理员限制用户创建房间 userId={} operatorId={} hours={}",
                userId, operatorId, req.getHours());
    }

    public void unbanCreate(Long userId, Long operatorId) {
        roomStateService.unbanCreate(userId);
        notifyOwner(userId, "创建房间限制已解除", "您可以重新创建音乐房。");
        log.info("管理员解除创建房间限制 userId={} operatorId={}", userId, operatorId);
    }

    private void notifyOwner(Long userId, String title, String content) {
        try {
            Notification notification = new Notification();
            notification.setUserId(userId);
            notification.setNotifyType(Notification.TYPE_SYSTEM);
            notification.setTitle(title);
            notification.setContent(content);
            notification.setIsRead(0);
            notificationService.createAndPush(notification);
        } catch (Exception e) {
            log.warn("房间管理通知失败 userId={} title={} cause={}", userId, title, e.toString());
        }
    }
}
