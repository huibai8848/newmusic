package com.example.newmusic.module.room.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.room.entity.Room;
import com.example.newmusic.module.room.entity.RoomMember;
import com.example.newmusic.module.room.mapper.RoomMapper;
import com.example.newmusic.module.room.mapper.RoomMemberMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 房间权限的唯一判定处。
 *
 * <p>把「谁是房主/房管/成员/游客」「谁能控播/管理」收在一个类里，而不是散落在
 * Controller、MicService、ModerationService 各自写一遍 if：权限矩阵是 PRD 里
 * 最容易出现“一处改了、另一处没改”的部分（AC-14-102）。
 */
@Component
public class RoomPermissionService {

    private final RoomMapper roomMapper;
    private final RoomMemberMapper memberMapper;
    private final RoomStateService stateService;
    private final RedisOps redisOps;

    public RoomPermissionService(RoomMapper roomMapper, RoomMemberMapper memberMapper,
                                 RoomStateService stateService, RedisOps redisOps) {
        this.roomMapper = roomMapper;
        this.memberMapper = memberMapper;
        this.stateService = stateService;
        this.redisOps = redisOps;
    }

    public Room requireRoom(Long roomId) {
        Room room = roomId == null ? null : roomMapper.selectById(roomId);
        if (room == null) {
            throw new BizException(ResultCode.ROOM_NOT_FOUND);
        }
        return room;
    }

    public Room requireRoomByNo(String roomNo) {
        String value = roomNo == null ? "" : roomNo.trim();
        if (value.isEmpty()) {
            throw new BizException(ResultCode.ROOM_NOT_FOUND);
        }
        Room room = roomMapper.selectOne(new QueryWrapper<Room>()
                .eq("room_no", value).last("LIMIT 1"));
        if (room == null) {
            throw new BizException(ResultCode.ROOM_NOT_FOUND);
        }
        return room;
    }

    /** 加入场景：关闭/解散都按“不存在”处理，避免泄露房间历史（EX-14-002-1）。 */
    public Room requireActiveForJoin(Long roomId) {
        Room room = requireRoom(roomId);
        if (!Integer.valueOf(Room.STATUS_ACTIVE).equals(room.getStatus())) {
            throw new BizException(ResultCode.ROOM_NOT_FOUND);
        }
        return room;
    }

    /** 房内操作：状态异常给出明确错误，前端可以提示“房间已关闭”。 */
    public Room requireActiveForOperation(Long roomId) {
        Room room = requireRoom(roomId);
        if (Integer.valueOf(Room.STATUS_CLOSED).equals(room.getStatus())) {
            throw new BizException(ResultCode.ROOM_CLOSED);
        }
        if (!Integer.valueOf(Room.STATUS_ACTIVE).equals(room.getStatus())) {
            throw new BizException(ResultCode.ROOM_STATUS_ABNORMAL);
        }
        return room;
    }

    public RoomMember getMember(Long roomId, Long userId) {
        if (roomId == null || userId == null) {
            return null;
        }
        return memberMapper.selectOne(new QueryWrapper<RoomMember>()
                .eq("room_id", roomId).eq("user_id", userId).last("LIMIT 1"));
    }

    public RoomMember requireMember(Long roomId, Long userId) {
        RoomMember member = getMember(roomId, userId);
        if (member == null || Integer.valueOf(RoomMember.ROLE_BLACKLIST).equals(member.getRoomRole())) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION);
        }
        return member;
    }

    /**
     * 角色判定：房主以 {@code t_room.owner_id} 为准，其次看成员表。
     *
     * @return -1 游客/不在房内；0 成员；1 房管；2 房主；9 黑名单
     */
    public int roleOf(Room room, Long userId) {
        if (room == null || userId == null) {
            return -1;
        }
        if (userId.equals(room.getOwnerId())) {
            return RoomMember.ROLE_OWNER;
        }
        RoomMember member = getMember(room.getId(), userId);
        if (member == null) {
            return stateService.isBlacklisted(room.getId(), userId)
                    ? RoomMember.ROLE_BLACKLIST : -1;
        }
        return member.getRoomRole() == null ? RoomMember.ROLE_MEMBER : member.getRoomRole();
    }

    public void requireOwner(Room room, Long userId) {
        if (!isOwner(room, userId)) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION);
        }
    }

    public boolean isOwner(Room room, Long userId) {
        return room != null && userId != null && userId.equals(room.getOwnerId());
    }

    public void requireAdminOrOwner(Room room, Long userId) {
        int role = roleOf(room, userId);
        if (role != RoomMember.ROLE_OWNER && role != RoomMember.ROLE_ADMIN) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION);
        }
    }

    /** 控播：房主与房管都可以（PRD 14 权限矩阵）。 */
    public boolean canControl(Room room, Long userId) {
        int role = roleOf(room, userId);
        return role == RoomMember.ROLE_OWNER || role == RoomMember.ROLE_ADMIN;
    }

    /**
     * WS 控播权限。与管理权限分开：自由播放房（playMode=2）允许所有在房成员控播，
     * 但点歌审核、禁言等管理动作仍只属于房主/房管。
     */
    public boolean canControlPlayback(Room room, Long userId) {
        int role = roleOf(room, userId);
        if (role == RoomMember.ROLE_OWNER || role == RoomMember.ROLE_ADMIN) {
            return true;
        }
        return Integer.valueOf(2).equals(room.getPlayMode()) && role >= RoomMember.ROLE_MEMBER;
    }

    public boolean isMuted(Room room, Long userId) {
        return stateService.muteRemainingSeconds(room.getId(), userId) > 0;
    }

    public boolean isBlacklisted(Room room, Long userId) {
        if (userId == null) {
            return false;
        }
        if (stateService.isBlacklisted(room.getId(), userId)) {
            return true;
        }
        RoomMember member = getMember(room.getId(), userId);
        return member != null && Integer.valueOf(RoomMember.ROLE_BLACKLIST).equals(member.getRoomRole());
    }

    /** 是否在房间的 Redis 在线集合里。HTTP 侧据此判断“在房内”而不是只看成员表。 */
    public boolean isOnline(Long roomId, Long userId) {
        return userId != null && redisOps.isMember(RedisKeys.roomOnline(roomId), String.valueOf(userId));
    }

    /**
     * 前端权限码。前端不自己推导房主/房管，而是照这个集合渲染按钮；
     * 服务端每个写接口仍会独立校验一次，前端权限码只是 UX，不是安全边界。
     */
    public Set<String> permissions(Room room, int role) {
        Set<String> perms = new LinkedHashSet<>();
        perms.add("room.view");
        perms.add("room.play");
        if (role < 0 || role == RoomMember.ROLE_BLACKLIST) {
            return perms;
        }
        perms.add("room.chat");
        perms.add("room.queue.add");
        perms.add("room.mic.apply");
        if (role == RoomMember.ROLE_ADMIN || role == RoomMember.ROLE_OWNER) {
            perms.add("room.control");
            perms.add("room.mic.manage");
            perms.add("room.moderate");
            perms.add("room.queue.approve");
            perms.add("room.notice");
            perms.add("room.update.partial");
        }
        if (role == RoomMember.ROLE_OWNER) {
            perms.add("room.admin.manage");
            perms.add("room.blacklist");
            perms.add("room.update");
            perms.add("room.close");
            perms.add("room.dissolve");
            perms.add("room.transfer");
        }
        return perms;
    }
}
