package com.example.newmusic.module.room.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.room.dto.KickReq;
import com.example.newmusic.module.room.dto.MuteReq;
import com.example.newmusic.module.room.entity.Room;
import com.example.newmusic.module.room.entity.RoomMember;
import com.example.newmusic.module.room.mapper.RoomMemberMapper;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.service.UserService;
import com.example.newmusic.module.ws.support.WsSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.CloseStatus;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 房间治理：房管、禁言、踢人、黑名单（FR-14-006）。
 *
 * <p>权限矩阵只在这里实现一次：
 * <ul>
 *   <li>房主不可被禁言/踢出（BR-14-027/040）；</li>
 *   <li>房管不可管理其他房管（BR-14-026/041），房主可以；</li>
 *   <li>永久禁言与黑名单仅房主可用（PRD FR-14-006）。</li>
 * </ul>
 *
 * <p>禁言/黑名单**双写 Redis 与 DB**：Handler 每条消息读 Redis（快），
 * 重连/换实例时从 DB 恢复。写入时先 Redis（立即生效）再 DB（持久化），
 * 即使 DB 写失败，最坏结果也只是重启后禁言丢失，而不会出现“写进了 DB 但用户还能说话”。
 */
@Slf4j
@Service
public class RoomModerationService {

    private static final int MAX_ADMINS = 10;
    private static final int MAX_BLACKLIST = 200;
    private static final CloseStatus KICKED = new CloseStatus(4003, "kicked from room");

    private final RoomPermissionService permissionService;
    private final RoomStateService stateService;
    private final RoomMemberMapper memberMapper;
    private final RoomMicService micService;
    private final RoomNotifier notifier;
    private final UserService userService;
    private final WsSessionRegistry registry;

    public RoomModerationService(RoomPermissionService permissionService,
                                 RoomStateService stateService, RoomMemberMapper memberMapper,
                                 RoomMicService micService, RoomNotifier notifier,
                                 UserService userService, WsSessionRegistry registry) {
        this.permissionService = permissionService;
        this.stateService = stateService;
        this.memberMapper = memberMapper;
        this.micService = micService;
        this.notifier = notifier;
        this.userService = userService;
        this.registry = registry;
    }

    // ------------------------------------------------------------------ 房管

    @Transactional
    public void setAdmin(Long roomId, Long operatorId, Long targetUserId, boolean admin) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireOwner(room, operatorId);
        if (targetUserId == null || targetUserId.equals(room.getOwnerId())) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION, "不能对房主执行该操作");
        }
        RoomMember member = permissionService.getMember(roomId, targetUserId);
        if (member == null || Integer.valueOf(RoomMember.ROLE_BLACKLIST).equals(member.getRoomRole())) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION, "该用户不在房间内");
        }
        int targetRole = member.getRoomRole() == null ? RoomMember.ROLE_MEMBER : member.getRoomRole();
        if (admin && targetRole == RoomMember.ROLE_ADMIN) {
            return;
        }
        if (!admin && targetRole != RoomMember.ROLE_ADMIN) {
            return;
        }
        if (admin && adminCount(roomId) >= MAX_ADMINS) {
            throw new BizException(ResultCode.ROOM_ADMIN_LIMIT);
        }
        int role = admin ? RoomMember.ROLE_ADMIN : RoomMember.ROLE_MEMBER;
        memberMapper.updateRole(roomId, targetUserId, role);
        broadcastMember(roomId, targetUserId, operatorId);
        notifier.notifyUser(targetUserId, operatorId, nickname(operatorId),
                admin ? "你已成为房管" : "你的房管身份已被撤销",
                admin ? "房主「" + nickname(operatorId) + "」将你设为房管" : "房主已撤销你的房管身份",
                room);
        notifier.system(roomId, operatorId,
                nickname(operatorId) + (admin ? " 将 " : " 撤销了 ")
                        + nickname(targetUserId) + (admin ? " 设为房管" : " 的房管身份"));
    }

    // ------------------------------------------------------------------ 禁言

    @Transactional
    public void mute(Long roomId, Long operatorId, MuteReq req) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireAdminOrOwner(room, operatorId);
        Long targetUserId = req.getUserId();
        int targetRole = permissionService.roleOf(room, targetUserId);
        if (targetRole == RoomMember.ROLE_OWNER) {
            throw new BizException(ResultCode.ROOM_OWNER_PROTECTED);
        }
        if (targetRole < 0 || targetRole == RoomMember.ROLE_BLACKLIST) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION, "该用户不在房间内");
        }
        if (targetRole == RoomMember.ROLE_ADMIN && !permissionService.isOwner(room, operatorId)) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION, "房管不能管理其他房管");
        }
        Integer minutes = req.getDurationMinutes();
        if (minutes == null || (minutes <= 0 && minutes != -1)) {
            throw new BizException(ResultCode.PARAM_INVALID, "禁言时长不合法");
        }
        if (minutes == -1 && !permissionService.isOwner(room, operatorId)) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION, "仅房主可永久禁言");
        }

        stateService.mute(roomId, targetUserId, minutes);
        LocalDateTime until = minutes < 0 ? null : LocalDateTime.now().plusMinutes(minutes);
        memberMapper.updateMute(roomId, targetUserId, 1, until);
        // EX-14-005-5：上麦后被禁言 → 自动下麦
        micService.releaseOnLeave(roomId, targetUserId);

        broadcastMember(roomId, targetUserId, operatorId);
        Map<String, Object> event = memberData(roomId, targetUserId, operatorId);
        notifier.userEvent(targetUserId, "room.member.update", event);

        String durationText = minutes < 0 ? "永久" : minutes + " 分钟";
        String reason = req.getReason() == null || req.getReason().trim().isEmpty()
                ? "" : "，原因：" + req.getReason().trim();
        notifier.notifyUser(targetUserId, operatorId, nickname(operatorId), "你已被禁言",
                "你被禁言 " + durationText + reason, room);
        notifier.system(roomId, operatorId,
                nickname(operatorId) + " 将 " + nickname(targetUserId)
                        + " 禁言 " + durationText + reason);
    }

    @Transactional
    public void unmute(Long roomId, Long operatorId, Long targetUserId) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireAdminOrOwner(room, operatorId);
        int targetRole = permissionService.roleOf(room, targetUserId);
        if (targetRole == RoomMember.ROLE_ADMIN && !permissionService.isOwner(room, operatorId)) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION, "房管不能管理其他房管");
        }
        stateService.unmute(roomId, targetUserId);
        memberMapper.updateMute(roomId, targetUserId, 0, null);
        broadcastMember(roomId, targetUserId, operatorId);
        notifier.userEvent(targetUserId, "room.member.update", memberData(roomId, targetUserId, operatorId));
        notifier.notifyUser(targetUserId, operatorId, nickname(operatorId),
                "禁言已解除", "房主/房管解除了你的禁言", room);
        notifier.system(roomId, operatorId,
                nickname(operatorId) + " 解除了 " + nickname(targetUserId) + " 的禁言");
    }

    // ------------------------------------------------------------------ 踢人

    @Transactional
    public void kick(Long roomId, Long operatorId, KickReq req) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireAdminOrOwner(room, operatorId);
        Long targetUserId = req.getUserId();
        int targetRole = permissionService.roleOf(room, targetUserId);
        if (targetRole == RoomMember.ROLE_OWNER) {
            throw new BizException(ResultCode.ROOM_OWNER_PROTECTED);
        }
        if (targetRole == RoomMember.ROLE_ADMIN && !permissionService.isOwner(room, operatorId)) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION, "房管不能踢出其他房管");
        }
        if (targetUserId.equals(operatorId)) {
            throw new BizException(ResultCode.PARAM_INVALID, "不能踢出自己");
        }

        // BR-14-019：先写冷却，再断开；即使用户随后立刻重连也会被拒绝
        stateService.markKicked(roomId, targetUserId);
        forceLeave(room, targetUserId, req.getReason());

        Map<String, Object> kicked = new LinkedHashMap<>();
        kicked.put("roomId", String.valueOf(roomId));
        kicked.put("userId", String.valueOf(targetUserId));
        kicked.put("reason", req.getReason());
        kicked.put("operatorId", String.valueOf(operatorId));
        kicked.put("remainingSeconds", stateService.kickRemainingSeconds(roomId, targetUserId));
        notifier.userEvent(targetUserId, "room.kicked", kicked);
        notifier.notifyUser(targetUserId, operatorId, nickname(operatorId), "你已被移出房间",
                "原因：" + req.getReason(), room);

        Map<String, Object> leave = memberData(roomId, targetUserId, operatorId);
        leave.put("reason", "kicked");
        leave.put("onlineCount", stateService.onlineUserIds(roomId).size());
        notifier.roomEvent(roomId, "room.member.leave", leave);
        notifier.system(roomId, operatorId,
                nickname(operatorId) + " 将 " + nickname(targetUserId)
                        + " 移出房间，原因：" + req.getReason());
        registry.closeUserRoomSessions(targetUserId, roomId, KICKED);
    }

    // ------------------------------------------------------------------ 黑名单

    @Transactional
    public void blacklist(Long roomId, Long operatorId, Long targetUserId) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireOwner(room, operatorId);
        if (targetUserId == null || targetUserId.equals(room.getOwnerId())) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION, "不能拉黑房主");
        }
        int role = permissionService.roleOf(room, targetUserId);
        if (role == RoomMember.ROLE_BLACKLIST) {
            return;
        }
        if (stateService.blacklistSize(roomId) >= MAX_BLACKLIST) {
            throw new BizException(ResultCode.ROOM_BLACKLIST_FULL);
        }
        stateService.addBlacklist(roomId, targetUserId);
        RoomMember member = permissionService.getMember(roomId, targetUserId);
        if (member == null) {
            member = new RoomMember();
            member.setRoomId(roomId);
            member.setUserId(targetUserId);
            member.setRoomRole(RoomMember.ROLE_BLACKLIST);
            member.setMuteStatus(0);
            member.setJoinTime(LocalDateTime.now());
            member.setTotalDuration(0L);
            member.setContribute(0);
            memberMapper.insert(member);
        } else {
            memberMapper.updateRole(roomId, targetUserId, RoomMember.ROLE_BLACKLIST);
        }
        forceLeave(room, targetUserId, "被房主拉黑");

        broadcastMember(roomId, targetUserId, operatorId);
        notifier.userEvent(targetUserId, "room.member.update",
                memberData(roomId, targetUserId, operatorId));
        notifier.notifyUser(targetUserId, operatorId, nickname(operatorId), "你已被拉黑",
                "你已被房主加入房间黑名单，无法进入该房间", room);
        notifier.system(roomId, operatorId,
                nickname(operatorId) + " 将 " + nickname(targetUserId) + " 加入黑名单");
        registry.closeUserRoomSessions(targetUserId, roomId,
                new CloseStatus(4004, "blacklisted"));
    }

    @Transactional
    public void unblacklist(Long roomId, Long operatorId, Long targetUserId) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireOwner(room, operatorId);
        stateService.removeBlacklist(roomId, targetUserId);
        RoomMember member = permissionService.getMember(roomId, targetUserId);
        if (member != null
                && Integer.valueOf(RoomMember.ROLE_BLACKLIST).equals(member.getRoomRole())) {
            memberMapper.updateRole(roomId, targetUserId, RoomMember.ROLE_MEMBER);
        }
        broadcastMember(roomId, targetUserId, operatorId);
    }

    // ------------------------------------------------------------------ 内部

    /** 强制离开：下麦、结算停留时长、断开本机连接。调用方负责广播。 */
    private void forceLeave(Room room, Long userId, String reason) {
        try {
            micService.releaseOnLeave(room.getId(), userId);
        } catch (Exception e) {
            log.warn("强制下麦失败 roomId={} userId={} cause={}", room.getId(), userId, e.toString());
        }
        try {
            memberMapper.accumulateDuration(room.getId(), userId, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("离开时长结算失败 roomId={} userId={} cause={}", room.getId(), userId, e.toString());
        }
        log.info("强制离开房间 roomId={} userId={} reason={}", room.getId(), userId, reason);
    }

    private void broadcastMember(Long roomId, Long userId, Long operatorId) {
        notifier.roomEvent(roomId, "room.member.update",
                memberData(roomId, userId, operatorId));
    }

    private Map<String, Object> memberData(Long roomId, Long userId, Long operatorId) {
        RoomMember member = permissionService.getMember(roomId, userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roomId", String.valueOf(roomId));
        data.put("userId", String.valueOf(userId));
        data.put("role", member == null || member.getRoomRole() == null
                ? RoomMember.ROLE_MEMBER : member.getRoomRole());
        long muteSeconds = stateService.muteRemainingSeconds(roomId, userId);
        data.put("muted", muteSeconds > 0);
        data.put("muteRemainingSeconds", muteSeconds);
        data.put("online", permissionService.isOnline(roomId, userId));
        data.put("operatorId", operatorId == null ? null : String.valueOf(operatorId));
        data.put("serverTime", System.currentTimeMillis());
        return data;
    }

    private long adminCount(Long roomId) {
        return memberMapper.selectCount(new QueryWrapper<RoomMember>()
                .eq("room_id", roomId).eq("room_role", RoomMember.ROLE_ADMIN));
    }

    private String nickname(Long userId) {
        if (userId == null) {
            return "系统";
        }
        try {
            User user = userService.getById(userId);
            if (user != null && user.getNickname() != null) {
                return user.getNickname();
            }
        } catch (Exception e) {
            log.debug("查询昵称失败 userId={} cause={}", userId, e.toString());
        }
        return "用户" + userId;
    }
}
