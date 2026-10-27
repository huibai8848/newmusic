package com.example.newmusic.module.room.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.room.dto.RoomCreateReq;
import com.example.newmusic.module.room.dto.RoomQueryReq;
import com.example.newmusic.module.room.dto.RoomUpdateReq;
import com.example.newmusic.module.room.entity.Room;
import com.example.newmusic.module.room.entity.RoomMember;
import com.example.newmusic.module.room.entity.RoomMic;
import com.example.newmusic.module.room.mapper.RoomMapper;
import com.example.newmusic.module.room.mapper.RoomMemberMapper;
import com.example.newmusic.module.room.mapper.RoomMicMapper;
import com.example.newmusic.module.room.vo.MicVO;
import com.example.newmusic.module.room.vo.QueueItemVO;
import com.example.newmusic.module.room.vo.RoomDetailVO;
import com.example.newmusic.module.room.vo.RoomMemberVO;
import com.example.newmusic.module.room.vo.RoomVO;
import com.example.newmusic.module.search.support.SensitiveWordSupport;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.service.UserService;
import com.example.newmusic.module.ws.service.WsPresenceService;
import com.example.newmusic.module.ws.support.WsSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 房间主流程：创建、列表、详情、加入、退出、关闭、重开、解散、转让。
 *
 * <p><b>权威数据划分</b>（系分 05 D-01）：
 * <ul>
 *   <li>{@code t_room} / {@code t_room_member} / {@code t_room_mic} / {@code t_room_queue}
 *       是房间配置与成员关系的权威；</li>
 *   <li>Redis 只保存实时态（在线集合、播放态、禁言/黑名单镜像），重启后能从 DB 重建；</li>
 *   <li>模块 04 的演示房间（DB 中不存在）继续走 {@code WsPresenceService#claimOwner}，
 *       这样 {@code verify-04.mjs} 不需要改动。</li>
 * </ul>
 *
 * <p><b>与 WS Handler 的分工</b>：本类负责“该不该让他进来”和“进来后要改哪些数据”，
 * Handler 负责协议与会话表。因此 HTTP join 与 WS join 复用同一个 {@link #prepareJoin}。
 */
@Slf4j
@Service
public class RoomService {

    private static final int DAILY_CREATE_LIMIT = 3;
    private static final int ACTIVE_ROOM_LIMIT = 10;
    private static final Set<Integer> MIC_COUNTS =
            new LinkedHashSet<>(Arrays.asList(4, 6, 8, 12));
    private static final Set<Integer> MAX_MEMBERS =
            new LinkedHashSet<>(Arrays.asList(50, 100, 200, 500));

    private final RoomMapper roomMapper;
    private final RoomMemberMapper memberMapper;
    private final RoomMicMapper micMapper;
    private final RoomPermissionService permissionService;
    private final RoomStateService stateService;
    private final RoomAssembler assembler;
    private final RoomNotifier notifier;
    private final RoomMicService micService;
    private final RoomQueueService queueService;
    private final WsPresenceService presence;
    private final WsSessionRegistry registry;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final SensitiveWordSupport sensitiveWords;

    /**
     * “这个 roomId 是否是真房间”的本地缓存。
     *
     * <p>WS 的每条聊天/弹幕都要判断一次，直接查库会把房间互动变成 DB 热点。
     * 30 秒的过期是刻意短的：解散后的房间最多多写 30 秒互动计数，
     * 这个代价远小于每条消息一次主键查询。
     */
    private final Cache<Long, Boolean> managedCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public RoomService(RoomMapper roomMapper, RoomMemberMapper memberMapper,
                       RoomMicMapper micMapper, RoomPermissionService permissionService,
                       RoomStateService stateService, RoomAssembler assembler,
                       RoomNotifier notifier, RoomMicService micService,
                       RoomQueueService queueService, WsPresenceService presence,
                       WsSessionRegistry registry, UserService userService,
                       PasswordEncoder passwordEncoder, SensitiveWordSupport sensitiveWords) {
        this.roomMapper = roomMapper;
        this.memberMapper = memberMapper;
        this.micMapper = micMapper;
        this.permissionService = permissionService;
        this.stateService = stateService;
        this.assembler = assembler;
        this.notifier = notifier;
        this.micService = micService;
        this.queueService = queueService;
        this.presence = presence;
        this.registry = registry;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.sensitiveWords = sensitiveWords;
    }

    // ------------------------------------------------------------------ 创建

    @Transactional
    public RoomDetailVO create(Long userId, RoomCreateReq req) {
        User user = userService.getByIdOrThrow(userId);
        if (Integer.valueOf(2).equals(user.getStatus())) {
            throw new BizException(ResultCode.ACCOUNT_BANNED);
        }
        if (stateService.isCreateBanned(userId)) {
            throw new BizException(ResultCode.ROOM_CREATE_BANNED,
                    "您已被管理员限制创建房间：" + stateService.createBanReason(userId));
        }
        String name = req.getName() == null ? "" : req.getName().trim();
        if (name.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "房间名不能为空");
        }
        if (sensitiveWords.isRisky(name) || sensitiveWords.isRisky(req.getNotice())) {
            throw new BizException(ResultCode.ROOM_NAME_ILLEGAL);
        }
        int roomType = req.getRoomType() == null ? Room.TYPE_PUBLIC : req.getRoomType();
        String password = req.getPassword() == null ? null : req.getPassword().trim();
        if (roomType == Room.TYPE_PASSWORD
                && (password == null || password.length() < 4 || password.length() > 16)) {
            throw new BizException(ResultCode.PARAM_INVALID, "密码房密码需为 4-16 位");
        }
        checkCreateQuota(userId);

        Room room = new Room();
        room.setRoomNo(nextRoomNo());
        room.setName(name);
        room.setCover(StringUtils.hasText(req.getCover()) ? req.getCover() : user.getAvatar());
        room.setOwnerId(userId);
        room.setRoomType(roomType);
        room.setPasswordHash(roomType == Room.TYPE_PASSWORD
                ? passwordEncoder.encode(password) : null);
        room.setNotice(req.getNotice());
        room.setThemeTags(req.getThemeTags());
        room.setMicCount(MIC_COUNTS.contains(req.getMicCount()) ? req.getMicCount() : 6);
        room.setMaxMembers(MAX_MEMBERS.contains(req.getMaxMembers()) ? req.getMaxMembers() : 100);
        room.setPlayMode(req.getPlayMode() == null || req.getPlayMode() < 1 || req.getPlayMode() > 3
                ? 1 : req.getPlayMode());
        room.setNeedApprove(req.getNeedApprove() == null ? 1 : (req.getNeedApprove() == 0 ? 0 : 1));
        room.setAllowFreeMic(req.getAllowFreeMic() == null ? 0 : (req.getAllowFreeMic() == 1 ? 1 : 0));
        room.setStatus(Room.STATUS_ACTIVE);
        room.setOnlineCount(1);
        room.setHotScore(0);
        room.setLastActiveTime(LocalDateTime.now());
        roomMapper.insert(room);

        RoomMember owner = new RoomMember();
        owner.setRoomId(room.getId());
        owner.setUserId(userId);
        owner.setRoomRole(RoomMember.ROLE_OWNER);
        owner.setMuteStatus(0);
        owner.setJoinTime(LocalDateTime.now());
        owner.setTotalDuration(0L);
        owner.setContribute(0);
        memberMapper.insert(owner);

        createMics(room);
        stateService.addRecentRoom(userId, room.getId());
        log.info("房间创建成功 roomId={} roomNo={} ownerId={}", room.getId(), room.getRoomNo(), userId);
        return assembler.detail(room, userId);
    }

    private void checkCreateQuota(Long userId) {
        long today = roomMapper.selectCount(new QueryWrapper<Room>()
                .eq("owner_id", userId)
                .ge("create_time", LocalDate.now().atStartOfDay()));
        long active = roomMapper.selectCount(new QueryWrapper<Room>()
                .eq("owner_id", userId).eq("status", Room.STATUS_ACTIVE));
        if (today >= DAILY_CREATE_LIMIT || active >= ACTIVE_ROOM_LIMIT) {
            throw new BizException(ResultCode.ROOM_CREATE_LIMIT);
        }
    }

    private String nextRoomNo() {
        for (int i = 0; i < 5; i++) {
            String candidate = String.valueOf(stateService.allocateRoomNo());
            long exists = roomMapper.selectCount(new QueryWrapper<Room>().eq("room_no", candidate));
            if (exists == 0) {
                return candidate;
            }
        }
        throw new BizException(ResultCode.SYSTEM_BUSY, "房间号分配失败，请重试");
    }

    /** 初始化麦位；1 号麦由房主占用（BR-14-010）。 */
    private void createMics(Room room) {
        int count = room.getMicCount() == null ? 6 : room.getMicCount();
        for (int pos = 1; pos <= count; pos++) {
            RoomMic mic = new RoomMic();
            mic.setRoomId(room.getId());
            mic.setMicPos(pos);
            mic.setStatus(RoomMic.STATUS_EMPTY);
            if (pos == 1) {
                mic.setUserId(room.getOwnerId());
                mic.setStatus(RoomMic.STATUS_OCCUPIED);
                stateService.occupyMic(room.getId(), pos, room.getOwnerId());
            }
            micMapper.insert(mic);
        }
    }

    // ------------------------------------------------------------------ 列表 / 详情

    public PageResult<RoomVO> page(RoomQueryReq query, Long viewerId) {
        query.normalize();
        String tab = query.getTab() == null ? "recommend" : query.getTab();
        Page<Room> page = query.toPage();
        QueryWrapper<Room> wrapper = new QueryWrapper<>();
        wrapper.eq("status", Room.STATUS_ACTIVE);
        if (!"mine".equals(tab)) {
            // BR-14-073：私密房不出现在任何列表
            wrapper.ne("room_type", Room.TYPE_PRIVATE);
        }
        if (query.getRoomType() != null) {
            wrapper.eq("room_type", query.getRoomType());
        }
        if (query.hasKeyword()) {
            wrapper.like("name", query.getKeyword());
        }
        switch (tab) {
            case "hot":
                wrapper.orderByDesc("online_count").orderByDesc("hot_score");
                break;
            case "new":
                wrapper.orderByDesc("create_time");
                break;
            case "mine":
                if (viewerId == null) {
                    throw new BizException(ResultCode.UNAUTHORIZED);
                }
                // userId 是 Long，拼接没有注入面；比子查询 + 两次往返更直接
                wrapper.and(q -> q.eq("owner_id", viewerId).or()
                        .inSql("id", "SELECT room_id FROM t_room_member WHERE deleted = 0 AND user_id = "
                                + viewerId));
                wrapper.orderByDesc("last_active_time");
                break;
            case "follow":
                // 关注关系归模块 10；在它交付前退化为推荐，而不是返回空列表骗用户
            case "recommend":
            default:
                wrapper.orderByDesc("hot_score").orderByDesc("online_count");
                break;
        }
        roomMapper.selectPage(page, wrapper);
        List<RoomVO> list = assembler.toVOs(page.getRecords());
        return pageResult(page, list, query.isTruncated());
    }

    public RoomDetailVO detailByNo(String roomNo, Long viewerId) {
        Room room = permissionService.requireRoomByNo(roomNo);
        return detail(room, viewerId);
    }

    public RoomDetailVO detailById(Long roomId, Long viewerId) {
        return detail(permissionService.requireRoom(roomId), viewerId);
    }

    private RoomDetailVO detail(Room room, Long viewerId) {
        if (Integer.valueOf(Room.STATUS_DISSOLVED).equals(room.getStatus())) {
            throw new BizException(ResultCode.ROOM_NOT_FOUND);
        }
        return assembler.detail(room, viewerId);
    }

    public Room requireRoom(Long roomId) {
        return permissionService.requireRoom(roomId);
    }

    // ------------------------------------------------------------------ 修改

    @Transactional
    public RoomDetailVO update(Long roomId, RoomUpdateReq req, Long operatorId) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireAdminOrOwner(room, operatorId);
        room.setVersion(req.getVersion());

        if (req.getName() != null) {
            String name = req.getName().trim();
            if (name.isEmpty()) {
                throw new BizException(ResultCode.PARAM_INVALID, "房间名不能为空");
            }
            if (sensitiveWords.isRisky(name)) {
                throw new BizException(ResultCode.ROOM_NAME_ILLEGAL);
            }
            room.setName(name);
        }
        if (req.getNotice() != null) {
            if (sensitiveWords.isRisky(req.getNotice())) {
                throw new BizException(ResultCode.ROOM_NAME_ILLEGAL);
            }
            room.setNotice(req.getNotice());
        }
        if (req.getCover() != null) {
            room.setCover(req.getCover());
        }
        if (req.getThemeTags() != null) {
            room.setThemeTags(req.getThemeTags());
        }
        if (req.getMaxMembers() != null) {
            if (!MAX_MEMBERS.contains(req.getMaxMembers())) {
                throw new BizException(ResultCode.PARAM_INVALID, "人数上限只能为 50/100/200/500");
            }
            room.setMaxMembers(req.getMaxMembers());
        }
        if (req.getPlayMode() != null) {
            if (req.getPlayMode() < 1 || req.getPlayMode() > 3) {
                throw new BizException(ResultCode.PARAM_INVALID, "播放模式不合法");
            }
            room.setPlayMode(req.getPlayMode());
        }
        if (req.getNeedApprove() != null) {
            room.setNeedApprove(req.getNeedApprove() == 0 ? 0 : 1);
        }
        if (req.getAllowFreeMic() != null) {
            room.setAllowFreeMic(req.getAllowFreeMic() == 1 ? 1 : 0);
        }

        int oldMicCount = room.getMicCount() == null ? 6 : room.getMicCount();
        int newMicCount = oldMicCount;
        if (req.getMicCount() != null) {
            if (!MIC_COUNTS.contains(req.getMicCount())) {
                throw new BizException(ResultCode.PARAM_INVALID, "麦位数只能为 4/6/8/12");
            }
            newMicCount = req.getMicCount();
            room.setMicCount(newMicCount);
        }
        boolean clearPassword = false;
        if (req.getRoomType() != null) {
            room.setRoomType(req.getRoomType());
            if (req.getRoomType() == Room.TYPE_PASSWORD) {
                if (StringUtils.hasText(req.getPassword())) {
                    if (req.getPassword().trim().length() < 4) {
                        throw new BizException(ResultCode.PARAM_INVALID, "密码需为 4-16 位");
                    }
                    room.setPasswordHash(passwordEncoder.encode(req.getPassword().trim()));
                    clearPassword = false;
                } else if (!StringUtils.hasText(room.getPasswordHash())) {
                    throw new BizException(ResultCode.PARAM_INVALID, "密码房必须设置密码");
                }
            } else {
                room.setPasswordHash(null);
                clearPassword = true;
            }
        } else if (StringUtils.hasText(req.getPassword())) {
            if (req.getPassword().trim().length() < 4) {
                throw new BizException(ResultCode.PARAM_INVALID, "密码需为 4-16 位");
            }
            room.setRoomType(Room.TYPE_PASSWORD);
            room.setPasswordHash(passwordEncoder.encode(req.getPassword().trim()));
        }

        if (roomMapper.updateById(room) == 0) {
            throw new BizException(ResultCode.CONCURRENT_MODIFY_CONFLICT);
        }
        if (clearPassword) {
            // updateById 的 NOT_NULL 策略不会把 null 写回库，清密码必须显式 SET NULL
            roomMapper.update(null, new UpdateWrapper<Room>()
                    .eq("id", roomId).set("password_hash", null));
            room.setPasswordHash(null);
        }
        if (newMicCount != oldMicCount) {
            resizeMics(room, oldMicCount, newMicCount);
        }
        broadcastRoom(room);
        return assembler.detail(room, operatorId);
    }

    @Transactional
    public RoomDetailVO updateNotice(Long roomId, Long operatorId, String notice) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireAdminOrOwner(room, operatorId);
        if (notice != null && sensitiveWords.isRisky(notice)) {
            throw new BizException(ResultCode.ROOM_NAME_ILLEGAL);
        }
        if (notice != null && notice.length() > 500) {
            throw new BizException(ResultCode.PARAM_INVALID, "公告不能超过 500 字");
        }
        room.setNotice(notice);
        roomMapper.updateById(room);
        notifier.roomEvent(roomId, "room.notice.update",
                single("roomId", String.valueOf(roomId), "notice", notice));
        return assembler.detail(room, operatorId);
    }

    private void resizeMics(Room room, int oldCount, int newCount) {
        if (newCount > oldCount) {
            Set<Integer> existing = new HashSet<>();
            for (RoomMic mic : micMapper.selectList(new QueryWrapper<RoomMic>()
                    .eq("room_id", room.getId()))) {
                existing.add(mic.getMicPos());
            }
            for (int pos = 1; pos <= newCount; pos++) {
                if (existing.contains(pos)) {
                    continue;
                }
                RoomMic mic = new RoomMic();
                mic.setRoomId(room.getId());
                mic.setMicPos(pos);
                mic.setStatus(RoomMic.STATUS_EMPTY);
                micMapper.insert(mic);
            }
            return;
        }
        List<RoomMic> removed = micMapper.selectList(new QueryWrapper<RoomMic>()
                .eq("room_id", room.getId()).gt("mic_pos", newCount));
        for (RoomMic mic : removed) {
            if (mic.getUserId() != null) {
                stateService.releaseMic(room.getId(), mic.getMicPos());
            }
        }
        micMapper.deleteAbove(room.getId(), newCount);
    }

    // ------------------------------------------------------------------ 加入 / 退出

    /**
     * 加入校验（HTTP 与 WS 共用）。不改变任何状态。
     *
     * <p>顺序与 PRD FR-14-002 一致：存在/活跃 → 黑名单 → 蹲禁 → 满员 → 密码 → 封禁。
     * 密码放在满员之后：满员房间不该让用户先输一遍密码再被拒。
     */
    public Room prepareJoin(Long roomId, Long userId, String password) {
        Room room = permissionService.requireActiveForJoin(roomId);
        if (permissionService.isBlacklisted(room, userId)) {
            throw new BizException(ResultCode.ROOM_BLACKLISTED);
        }
        long kicked = stateService.kickRemainingSeconds(roomId, userId);
        if (kicked > 0) {
            throw new BizException(ResultCode.ROOM_KICK_COOLDOWN,
                    "你已被移出房间，请在 " + kicked + " 秒后再试");
        }
        boolean online = permissionService.isOnline(roomId, userId);
        int maxMembers = room.getMaxMembers() == null ? 100 : room.getMaxMembers();
        if (!online && presence.onlineCount(roomId) >= maxMembers) {
            throw new BizException(ResultCode.ROOM_FULL);
        }
        if (Integer.valueOf(Room.TYPE_PASSWORD).equals(room.getRoomType())
                && !permissionService.isOwner(room, userId)
                && !stateService.isPasswordVerified(roomId, userId)) {
            checkPassword(room, userId, password);
        }
        User user = userService.getByIdOrThrow(userId);
        if (Integer.valueOf(2).equals(user.getStatus())) {
            throw new BizException(ResultCode.ACCOUNT_BANNED);
        }
        return room;
    }

    private void checkPassword(Room room, Long userId, String password) {
        long fails = stateService.passwordFailCount(room.getId(), userId);
        if (fails >= 3) {
            throw new BizException(ResultCode.ROOM_PASSWORD_LOCKED);
        }
        if (!StringUtils.hasText(password)) {
            throw new BizException(ResultCode.ROOM_PASSWORD_ERROR, "请输入房间密码");
        }
        if (!passwordEncoder.matches(password.trim(), room.getPasswordHash())) {
            long current = stateService.recordPasswordFail(room.getId(), userId);
            if (current >= 3) {
                throw new BizException(ResultCode.ROOM_PASSWORD_LOCKED);
            }
            throw new BizException(ResultCode.ROOM_PASSWORD_ERROR,
                    "房间密码错误，还可尝试 " + (3 - current) + " 次");
        }
        stateService.markPasswordVerified(room.getId(), userId);
        stateService.clearPasswordFail(room.getId(), userId);
    }

    /** HTTP 加入：只记录成员关系；在线状态与 WS 连接绑定，不在这里伪造。 */
    @Transactional
    public RoomDetailVO join(Long roomId, Long userId, String password) {
        Room room = prepareJoin(roomId, userId, password);
        bindMember(room, userId, false);
        stateService.addRecentRoom(userId, roomId);
        return assembler.detail(room, userId);
    }

    /** WS 加入成功后调用：成员落库、最近访问、在线镜像、房主回位。 */
    @Transactional
    public void onWsJoin(Room room, Long userId) {
        bindMember(room, userId, true);
        stateService.addRecentRoom(userId, room.getId());
        roomMapper.updateOnline(room.getId(), presence.onlineCount(room.getId()), LocalDateTime.now());
        if (permissionService.isOwner(room, userId)) {
            occupyOwnerSeatIfFree(room);
        }
    }

    private void bindMember(Room room, Long userId, boolean online) {
        LocalDateTime now = LocalDateTime.now();
        RoomMember member = permissionService.getMember(room.getId(), userId);
        if (member == null) {
            member = new RoomMember();
            member.setRoomId(room.getId());
            member.setUserId(userId);
            member.setRoomRole(RoomMember.ROLE_MEMBER);
            member.setMuteStatus(0);
            member.setJoinTime(now);
            member.setLastEnterTime(online ? now : null);
            member.setTotalDuration(0L);
            member.setContribute(0);
            memberMapper.insert(member);
            return;
        }
        if (Integer.valueOf(RoomMember.ROLE_BLACKLIST).equals(member.getRoomRole())) {
            throw new BizException(ResultCode.ROOM_BLACKLISTED);
        }
        member.setLastEnterTime(online ? now : member.getLastEnterTime());
        member.setLeaveTime(null);
        memberMapper.updateById(member);
    }

    private void occupyOwnerSeatIfFree(Room room) {
        RoomMic seat = micMapper.selectOne(new QueryWrapper<RoomMic>()
                .eq("room_id", room.getId()).eq("mic_pos", 1).last("LIMIT 1"));
        if (seat == null) {
            return;
        }
        if (room.getOwnerId().equals(seat.getUserId())) {
            return;
        }
        if (micMapper.occupy(seat.getId(), room.getOwnerId()) == 1) {
            stateService.occupyMic(room.getId(), 1, room.getOwnerId());
        }
    }

    /**
     * WS 连接关闭 / HTTP 退出后调用（仅当该用户在本房间已没有其它连接）。
     *
     * <p>停留时长用 SQL 的 {@code TIMESTAMPDIFF} 结算并以 {@code last_enter_time IS NOT NULL}
     * 做幂等，因此断线回调与显式 leave 同时到达也只会计一次。
     */
    @Transactional
    public void onWsLeave(Long roomId, Long userId) {
        Room room = roomMapper.selectById(roomId);
        if (room == null) {
            return;
        }
        long seconds = 0L;
        RoomMember member = permissionService.getMember(roomId, userId);
        if (member != null && member.getLastEnterTime() != null) {
            seconds = Math.max(0L, Duration.between(member.getLastEnterTime(), LocalDateTime.now())
                    .getSeconds());
        }
        memberMapper.accumulateDuration(roomId, userId, LocalDateTime.now());
        if (seconds > 0) {
            roomMapper.addDuration(roomId, seconds);
        }
        micService.releaseOnLeave(roomId, userId);
        if (presence.onlineCount(roomId) > 0) {
            roomMapper.updateOnline(roomId, presence.onlineCount(roomId), LocalDateTime.now());
        } else {
            roomMapper.touchActive(roomId, LocalDateTime.now());
        }
        maybeTransferOwner(room, userId);
    }

    /**
     * 房主离开且不再在线时自动交接（FR-14-009）。
     *
     * <p>候选顺序：房管（贡献值降序、加入时间升序）→ 在线成员（同序）。
     * 找不到候选就保持房主不变：房间进入空房状态，等房主回来。
     */
    private void maybeTransferOwner(Room room, Long leavingUserId) {
        if (!Integer.valueOf(Room.STATUS_ACTIVE).equals(room.getStatus())
                || !leavingUserId.equals(room.getOwnerId())) {
            return;
        }
        Set<Long> online = stateService.onlineUserIds(room.getId());
        if (online.contains(room.getOwnerId())) {
            return; // 房主还有别的设备在线，不算离开
        }
        List<RoomMember> candidates = memberMapper.selectList(new QueryWrapper<RoomMember>()
                .eq("room_id", room.getId())
                .ne("user_id", room.getOwnerId())
                .ne("room_role", RoomMember.ROLE_BLACKLIST)
                .orderByDesc("room_role")
                .orderByDesc("contribute")
                .orderByAsc("join_time")
                .last("LIMIT 50"));
        for (RoomMember candidate : candidates) {
            if (candidate.getUserId() != null && online.contains(candidate.getUserId())) {
                doTransfer(room, room.getOwnerId(), candidate.getUserId(), true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ 关闭 / 重开 / 解散 / 转让

    @Transactional
    public void close(Long roomId, Long operatorId) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireOwner(room, operatorId);
        notifier.roomEvent(roomId, "room.closed",
                data(roomId, "closed", "房主关闭了房间", operatorId));
        // 先落状态再断开：连接关闭回调里的 onWsLeave 会检查房间状态，
        // 若不先置为 closed，房主关房的那一刻会被“自动交接”给别的成员。
        room.setStatus(Room.STATUS_CLOSED);
        room.setOnlineCount(0);
        roomMapper.updateById(room);
        registry.closeRoomSessions(roomId, new CloseStatus(4005, "room closed"));
        notifyMembers(room, "房间已关闭", "房主关闭了房间，24 小时内可重新开播");
    }

    @Transactional
    public RoomDetailVO reopen(Long roomId, Long operatorId) {
        Room room = permissionService.requireRoom(roomId);
        permissionService.requireOwner(room, operatorId);
        if (!Integer.valueOf(Room.STATUS_CLOSED).equals(room.getStatus())) {
            throw new BizException(ResultCode.ROOM_STATUS_ABNORMAL, "只有已关闭的房间可以重开");
        }
        LocalDateTime closedAt = room.getUpdateTime() == null ? room.getCreateTime() : room.getUpdateTime();
        if (closedAt != null && closedAt.plusHours(24).isBefore(LocalDateTime.now())) {
            throw new BizException(ResultCode.ROOM_STATUS_ABNORMAL, "关闭超过 24 小时，房间已无法重开");
        }
        room.setStatus(Room.STATUS_ACTIVE);
        room.setLastActiveTime(LocalDateTime.now());
        roomMapper.updateById(room);
        notifier.roomEvent(roomId, "room.reopened",
                data(roomId, "reopened", "房间已重新开播", operatorId));
        return assembler.detail(room, operatorId);
    }

    /**
     * 解散。顺序是硬约束（BR-14-068）：先广播，再断开，最后清理 Redis。
     * 反过来用户会看到连接无缘无故消失，且再也收不到“房间已解散”。
     */
    @Transactional
    public void dissolve(Long roomId, Long operatorId, String confirmName) {
        Room room = permissionService.requireRoom(roomId);
        permissionService.requireOwner(room, operatorId);
        if (!Integer.valueOf(Room.STATUS_ACTIVE).equals(room.getStatus())
                && !Integer.valueOf(Room.STATUS_CLOSED).equals(room.getStatus())) {
            throw new BizException(ResultCode.ROOM_STATUS_ABNORMAL);
        }
        if (confirmName == null || !confirmName.trim().equals(room.getName())) {
            throw new BizException(ResultCode.PARAM_INVALID, "请输入正确的房间名以确认解散");
        }

        notifier.roomEvent(roomId, "room.closed",
                data(roomId, "dissolved", "房间已解散", operatorId));
        notifyMembers(room, "房间已解散", "房主解散了「" + room.getName() + "」");
        List<Long> online = new ArrayList<>(stateService.onlineUserIds(roomId));

        // 状态先落库，避免关闭回调触发房主自动交接；广播仍发生在断开之前
        room.setStatus(Room.STATUS_DISSOLVED);
        room.setOnlineCount(0);
        roomMapper.updateById(room);

        registry.closeRoomSessions(roomId, new CloseStatus(4005, "room dissolved"));
        stateService.clearRoom(roomId, online);
    }

    @Transactional
    public void transfer(Long roomId, Long operatorId, Long targetUserId) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireOwner(room, operatorId);
        if (targetUserId == null || targetUserId.equals(room.getOwnerId())) {
            throw new BizException(ResultCode.PARAM_INVALID, "转让目标不合法");
        }
        RoomMember target = permissionService.getMember(roomId, targetUserId);
        if (target == null || Integer.valueOf(RoomMember.ROLE_BLACKLIST).equals(target.getRoomRole())) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION, "转让目标必须是房间成员");
        }
        if (!permissionService.isOnline(roomId, targetUserId)) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION, "转让目标当前不在房间内");
        }
        doTransfer(room, operatorId, targetUserId, true);
    }

    private void doTransfer(Room room, Long oldOwnerId, Long newOwnerId, boolean oldKeepAdmin) {
        room.setOwnerId(newOwnerId);
        roomMapper.updateById(room);
        memberMapper.updateRole(room.getId(), newOwnerId, RoomMember.ROLE_OWNER);
        memberMapper.updateRole(room.getId(), oldOwnerId,
                oldKeepAdmin ? RoomMember.ROLE_ADMIN : RoomMember.ROLE_MEMBER);

        Map<String, Object> update = data(room.getId(), "owner_changed",
                "房主已变更", newOwnerId);
        update.put("oldOwnerId", String.valueOf(oldOwnerId));
        update.put("newOwnerId", String.valueOf(newOwnerId));
        notifier.roomEvent(room.getId(), "room.owner.changed", update);
        notifier.roomEvent(room.getId(), "room.member.update",
                single("roomId", String.valueOf(room.getId()),
                        "userId", String.valueOf(newOwnerId), "role", RoomMember.ROLE_OWNER));
        notifier.notifyUser(newOwnerId, oldOwnerId, "房主", "你已成为房主",
                "你已成为「" + room.getName() + "」的房主", room);
        notifier.system(room.getId(), oldOwnerId, "房主已转让");
        log.info("房主转让 roomId={} old={} new={}", room.getId(), oldOwnerId, newOwnerId);
    }

    private void notifyMembers(Room room, String title, String content) {
        List<RoomMember> members = memberMapper.selectList(new QueryWrapper<RoomMember>()
                .eq("room_id", room.getId())
                .ne("room_role", RoomMember.ROLE_BLACKLIST)
                .last("LIMIT 500"));
        List<Long> ids = new ArrayList<>(members.size());
        for (RoomMember member : members) {
            ids.add(member.getUserId());
        }
        notifier.notifyBatch(ids, title, content, room);
    }

    // ------------------------------------------------------------------ WS 集成

    /** DB 中是否存在该房间；不存在则走模块 04 的演示房间逻辑。 */
    public Room findManagedRoom(Long roomId) {
        return roomId == null ? null : roomMapper.selectById(roomId);
    }

    public boolean isManagedRoom(Long roomId) {
        if (roomId == null) {
            return false;
        }
        return managedCache.get(roomId, id -> roomMapper.selectById(id) != null);
    }

    public Map<String, Object> wsSnapshot(Room room, Long userId, String nickname) {
        return assembler.wsSnapshot(room, userId, nickname);
    }

    /** WS 控播权限：真实房间按 DB 角色，演示房间仍由 Handler 用 Redis 房主键判断。 */
    public boolean canControl(Room room, Long userId) {
        return permissionService.canControl(room, userId);
    }

    /** 真实房间的 WS 控播前置校验（状态 + 黑名单 + 权限）。 */
    public Room requireWsControl(Long roomId, Long userId) {
        Room room = permissionService.requireActiveForOperation(roomId);
        if (permissionService.isBlacklisted(room, userId)) {
            throw new BizException(ResultCode.ROOM_BLACKLISTED);
        }
        if (!permissionService.canControlPlayback(room, userId)) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION);
        }
        return room;
    }

    /** 切歌/控播成功后同步 DB 当前歌曲并消费队列。 */
    public void onPlayStateChanged(Long roomId, Long previousSongId, Long currentSongId) {
        try {
            Room room = roomMapper.selectById(roomId);
            if (room == null) {
                return;
            }
            roomMapper.updateCurrentSong(roomId, currentSongId);
            queueService.onSongChanged(roomId, previousSongId);
        } catch (Exception e) {
            log.warn("控播状态同步数据库失败（实时播放不受影响） roomId={} cause={}",
                    roomId, e.toString());
        }
    }

    public void recordInteraction(Long roomId) {
        if (isManagedRoom(roomId)) {
            stateService.recordInteraction(roomId);
        }
    }

    public List<RoomMemberVO> members(Long roomId) {
        return assembler.members(roomId);
    }

    public List<MicVO> mics(Long roomId) {
        return assembler.mics(roomId);
    }

    public List<QueueItemVO> queue(Long roomId) {
        return assembler.queue(roomId);
    }

    // ------------------------------------------------------------------ helpers

    private void broadcastRoom(Room room) {
        Map<String, Object> data = single("roomId", String.valueOf(room.getId()),
                "room", assembler.toVO(room));
        notifier.roomEvent(room.getId(), "room.update", data);
    }

    private static Map<String, Object> data(Long roomId, String reason, String message,
                                            Long operatorId) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("roomId", String.valueOf(roomId));
        data.put("reason", reason);
        data.put("message", message);
        data.put("operatorId", operatorId == null ? null : String.valueOf(operatorId));
        data.put("serverTime", System.currentTimeMillis());
        return data;
    }

    private static Map<String, Object> single(Object... kv) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            data.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return data;
    }

    private static PageResult<RoomVO> pageResult(Page<Room> page, List<RoomVO> list,
                                                 boolean truncated) {
        PageResult<RoomVO> result = new PageResult<>();
        result.setList(list);
        result.setTotal(page.getTotal());
        result.setPageNum(page.getCurrent());
        result.setPageSize(page.getSize());
        result.setPages(page.getPages());
        result.setHasNext(page.getCurrent() < page.getPages());
        result.setTruncated(truncated);
        return result;
    }
}
