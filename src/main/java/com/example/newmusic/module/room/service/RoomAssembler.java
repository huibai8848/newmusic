package com.example.newmusic.module.room.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.service.SongService;
import com.example.newmusic.module.room.entity.Room;
import com.example.newmusic.module.room.entity.RoomMember;
import com.example.newmusic.module.room.entity.RoomMic;
import com.example.newmusic.module.room.entity.RoomQueue;
import com.example.newmusic.module.room.mapper.RoomMemberMapper;
import com.example.newmusic.module.room.mapper.RoomMicMapper;
import com.example.newmusic.module.room.mapper.RoomQueueMapper;
import com.example.newmusic.module.room.vo.MicVO;
import com.example.newmusic.module.room.vo.QueueItemVO;
import com.example.newmusic.module.room.vo.RoomDetailVO;
import com.example.newmusic.module.room.vo.RoomMemberVO;
import com.example.newmusic.module.room.vo.RoomVO;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.service.UserService;
import com.example.newmusic.module.ws.service.WsPresenceService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 房间实体到 VO / WS 快照的组装。
 *
 * <p>三个约束决定了它必须集中在一处：
 * <ol>
 *   <li>{@code passwordHash} **永远不能**出现在任何响应里——一旦某个 VO 手滑带上，
 *       密码房就形同虚设；这里从实体逐字段搬运，不整对象序列化；</li>
 *   <li>用户昵称/头像要批量查，不能每个房间、每个成员各查一次；</li>
 *   <li>「在线人数 / 当前播放」以 Redis 为准，不能读 {@code t_room} 的落库镜像。</li>
 * </ol>
 */
@Component
public class RoomAssembler {

    /** 成员列表一次最多下发 300 人；超过的成员仍可通过分页接口获取 */
    private static final int MEMBER_LIMIT = 300;
    /** 队列一次最多下发 100 首（与 BR-14-048 的上限一致） */
    private static final int QUEUE_LIMIT = 100;

    private final UserService userService;
    private final SongService songService;
    private final WsPresenceService presence;
    private final RoomStateService stateService;
    private final RoomPermissionService permissionService;
    private final RoomMemberMapper memberMapper;
    private final RoomMicMapper micMapper;
    private final RoomQueueMapper queueMapper;

    public RoomAssembler(UserService userService, SongService songService,
                         WsPresenceService presence, RoomStateService stateService,
                         RoomPermissionService permissionService, RoomMemberMapper memberMapper,
                         RoomMicMapper micMapper, RoomQueueMapper queueMapper) {
        this.userService = userService;
        this.songService = songService;
        this.presence = presence;
        this.stateService = stateService;
        this.permissionService = permissionService;
        this.memberMapper = memberMapper;
        this.micMapper = micMapper;
        this.queueMapper = queueMapper;
    }

    // ------------------------------------------------------------------ 房间

    public RoomVO toVO(Room room) {
        return toVO(room, Collections.emptyMap(), Collections.emptyMap());
    }

    /** 列表页批量组装：owner 与 currentSong 各只查一次。 */
    public List<RoomVO> toVOs(List<Room> rooms) {
        if (rooms == null || rooms.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> userIds = new HashSet<>();
        Set<Long> songIds = new HashSet<>();
        for (Room room : rooms) {
            if (room.getOwnerId() != null) {
                userIds.add(room.getOwnerId());
            }
            if (room.getCurrentSongId() != null) {
                songIds.add(room.getCurrentSongId());
            }
        }
        Map<Long, User> users = users(userIds);
        Map<Long, Song> songs = songs(songIds);
        List<RoomVO> result = new ArrayList<>(rooms.size());
        for (Room room : rooms) {
            result.add(toVO(room, users, songs));
        }
        return result;
    }

    private RoomVO toVO(Room room, Map<Long, User> users, Map<Long, Song> songs) {
        RoomVO vo = new RoomVO();
        vo.setId(room.getId());
        vo.setRoomNo(room.getRoomNo());
        vo.setName(room.getName());
        vo.setCover(room.getCover());
        vo.setOwnerId(room.getOwnerId());
        User owner = users.get(room.getOwnerId());
        if (owner == null && room.getOwnerId() != null) {
            owner = userService.getById(room.getOwnerId());
        }
        if (owner != null) {
            vo.setOwnerNickname(owner.getNickname());
            vo.setOwnerAvatar(owner.getAvatar());
        }
        vo.setRoomType(room.getRoomType());
        vo.setPasswordRequired(StringUtils.hasText(room.getPasswordHash()));
        vo.setNotice(room.getNotice());
        vo.setThemeTags(room.getThemeTags());
        vo.setMicCount(room.getMicCount());
        vo.setMaxMembers(room.getMaxMembers());
        vo.setPlayMode(room.getPlayMode());
        vo.setNeedApprove(room.getNeedApprove());
        vo.setAllowFreeMic(room.getAllowFreeMic());
        vo.setStatus(room.getStatus());
        vo.setOnlineCount(presence.onlineCount(room.getId()));
        vo.setHotScore(room.getHotScore());
        vo.setCreateTime(room.getCreateTime());
        vo.setLastActiveTime(room.getLastActiveTime());

        Map<String, String> play = presence.playState(room.getId());
        String songId = play.get("songId");
        String songName = play.get("songName");
        String artistName = play.get("artistName");
        String cover = play.get("cover");
        Long duration = parseLong(play.get("duration"));
        long position = parseLong(play.get("position"), 0L);
        boolean playing = Boolean.parseBoolean(play.getOrDefault("isPlaying", "false"));

        if (!StringUtils.hasText(songId) && room.getCurrentSongId() != null) {
            songId = String.valueOf(room.getCurrentSongId());
            Song song = songs.get(room.getCurrentSongId());
            if (song == null) {
                song = firstSong(room.getCurrentSongId());
            }
            if (song != null) {
                songName = song.getName();
                artistName = song.getArtistName();
                cover = song.getCover();
                duration = song.getDuration() == null ? null : song.getDuration().longValue();
            }
        }
        // 播放中的进度是从“最后一次写入位置”外推的，否则卡片上的进度会停在切歌那一刻
        if (playing && StringUtils.hasText(songId)) {
            long serverTime = parseLong(play.get("serverTime"), System.currentTimeMillis());
            position += Math.max(0L, (System.currentTimeMillis() - serverTime) / 1000L);
        }
        vo.setCurrentSongId(songId);
        vo.setCurrentSongName(songName);
        vo.setCurrentArtistName(artistName);
        vo.setCurrentCover(cover);
        vo.setCurrentDuration(duration == null ? null : duration.intValue());
        vo.setPlayedPosition(position);
        vo.setPlaying(playing);
        return vo;
    }

    // ------------------------------------------------------------------ 详情

    public RoomDetailVO detail(Room room, Long viewerId) {
        RoomDetailVO vo = new RoomDetailVO();
        copy(room, vo);

        int role = permissionService.roleOf(room, viewerId);
        vo.setMyRole(role);
        vo.setMyPermissions(permissionService.permissions(room, role));
        vo.setMyMuteRemainingSeconds(viewerId == null ? 0L
                : stateService.muteRemainingSeconds(room.getId(), viewerId));
        vo.setKickedRemainingSeconds(viewerId == null ? 0L
                : stateService.kickRemainingSeconds(room.getId(), viewerId));

        Set<Long> online = stateService.onlineUserIds(room.getId());
        List<RoomMember> members = listMembers(room.getId());
        Map<Long, User> users = users(memberUserIds(members, room.getOwnerId()));
        vo.setMembers(memberVOs(members, users, online));
        vo.setMics(micVOs(room, users, online));
        vo.setQueue(queueVOs(room.getId(), users));
        vo.setServerTime(LocalDateTime.now());
        return vo;
    }

    private void copy(Room room, RoomVO vo) {
        RoomVO base = toVO(room);
        vo.setId(base.getId());
        vo.setRoomNo(base.getRoomNo());
        vo.setName(base.getName());
        vo.setCover(base.getCover());
        vo.setOwnerId(base.getOwnerId());
        vo.setOwnerNickname(base.getOwnerNickname());
        vo.setOwnerAvatar(base.getOwnerAvatar());
        vo.setRoomType(base.getRoomType());
        vo.setPasswordRequired(base.getPasswordRequired());
        vo.setNotice(base.getNotice());
        vo.setThemeTags(base.getThemeTags());
        vo.setMicCount(base.getMicCount());
        vo.setMaxMembers(base.getMaxMembers());
        vo.setPlayMode(base.getPlayMode());
        vo.setNeedApprove(base.getNeedApprove());
        vo.setAllowFreeMic(base.getAllowFreeMic());
        vo.setStatus(base.getStatus());
        vo.setOnlineCount(base.getOnlineCount());
        vo.setHotScore(base.getHotScore());
        vo.setCurrentSongId(base.getCurrentSongId());
        vo.setCurrentSongName(base.getCurrentSongName());
        vo.setCurrentArtistName(base.getCurrentArtistName());
        vo.setCurrentCover(base.getCurrentCover());
        vo.setCurrentDuration(base.getCurrentDuration());
        vo.setPlayedPosition(base.getPlayedPosition());
        vo.setPlaying(base.getPlaying());
        vo.setCreateTime(base.getCreateTime());
        vo.setLastActiveTime(base.getLastActiveTime());
    }

    // ------------------------------------------------------------------ 成员

    public List<RoomMemberVO> members(Long roomId) {
        Room room = permissionService.requireRoom(roomId);
        List<RoomMember> members = listMembers(roomId);
        Map<Long, User> users = users(memberUserIds(members, room.getOwnerId()));
        return memberVOs(members, users, stateService.onlineUserIds(roomId));
    }

    private List<RoomMember> listMembers(Long roomId) {
        return memberMapper.selectList(new QueryWrapper<RoomMember>()
                .eq("room_id", roomId)
                .ne("room_role", RoomMember.ROLE_BLACKLIST)
                .orderByDesc("room_role")
                .orderByAsc("join_time")
                .last("LIMIT " + MEMBER_LIMIT));
    }

    private Set<Long> memberUserIds(List<RoomMember> members, Long ownerId) {
        Set<Long> ids = new HashSet<>();
        if (ownerId != null) {
            ids.add(ownerId);
        }
        for (RoomMember member : members) {
            ids.add(member.getUserId());
        }
        return ids;
    }

    private List<RoomMemberVO> memberVOs(List<RoomMember> members, Map<Long, User> users,
                                         Set<Long> online) {
        List<RoomMemberVO> result = new ArrayList<>(members.size());
        for (RoomMember member : members) {
            RoomMemberVO vo = new RoomMemberVO();
            vo.setUserId(member.getUserId());
            User user = users.get(member.getUserId());
            if (user != null) {
                vo.setNickname(user.getNickname());
                vo.setAvatar(user.getAvatar());
            }
            vo.setRole(member.getRoomRole() == null ? RoomMember.ROLE_MEMBER : member.getRoomRole());
            vo.setOnline(online.contains(member.getUserId()));
            long muteSeconds = stateService.muteRemainingSeconds(member.getRoomId(), member.getUserId());
            vo.setMuted(muteSeconds > 0);
            vo.setMuteUntil(muteSeconds > 0
                    ? LocalDateTime.now().plusSeconds(muteSeconds) : null);
            vo.setJoinTime(member.getJoinTime());
            vo.setTotalDuration(member.getTotalDuration());
            vo.setContribute(member.getContribute());
            result.add(vo);
        }
        return result;
    }

    // ------------------------------------------------------------------ 麦位

    public List<MicVO> mics(Long roomId) {
        Room room = permissionService.requireRoom(roomId);
        List<RoomMic> rows = listMics(roomId);
        Set<Long> ids = new HashSet<>();
        for (RoomMic row : rows) {
            if (row.getUserId() != null) {
                ids.add(row.getUserId());
            }
        }
        return micVOs(room, users(ids), stateService.onlineUserIds(roomId));
    }

    private List<RoomMic> listMics(Long roomId) {
        return micMapper.selectList(new QueryWrapper<RoomMic>()
                .eq("room_id", roomId)
                .orderByAsc("mic_pos"));
    }

    private List<MicVO> micVOs(Room room, Map<Long, User> users, Set<Long> online) {
        List<RoomMic> rows = listMics(room.getId());
        List<MicVO> result = new ArrayList<>(rows.size());
        for (RoomMic row : rows) {
            MicVO vo = new MicVO();
            vo.setMicPos(row.getMicPos());
            vo.setUserId(row.getUserId());
            User user = row.getUserId() == null ? null : users.get(row.getUserId());
            if (user != null) {
                vo.setNickname(user.getNickname());
                vo.setAvatar(user.getAvatar());
                if (!online.isEmpty()) {
                    // 在线集合为空时（Redis 降级）不把“在线”误报成 false，前端按未知处理
                    vo.setOnline(online.contains(user.getId()));
                }
            }
            vo.setStatus(row.getStatus());
            vo.setLocked(Integer.valueOf(RoomMic.STATUS_LOCKED).equals(row.getStatus()));
            vo.setOwnerSeat(row.getMicPos() != null && row.getMicPos() == 1);
            result.add(vo);
        }
        return result;
    }

    // ------------------------------------------------------------------ 队列

    public List<QueueItemVO> queue(Long roomId) {
        return queueVOs(roomId, Collections.emptyMap());
    }

    /** 单个队列项（点歌成功/审批后的响应）。 */
    public QueueItemVO queueItem(RoomQueue row) {
        Map<Long, User> users = row.getUserId() == null
                ? Collections.emptyMap()
                : users(Collections.singleton(row.getUserId()));
        List<QueueItemVO> items = queueVOs(Collections.singletonList(row), users);
        return items.get(0);
    }

    private List<QueueItemVO> queueVOs(Collection<RoomQueue> rows, Map<Long, User> users) {
        List<QueueItemVO> result = new ArrayList<>(rows.size());
        for (RoomQueue row : rows) {
            QueueItemVO vo = new QueueItemVO();
            vo.setId(row.getId());
            vo.setRoomId(row.getRoomId());
            vo.setUserId(row.getUserId());
            User user = users.get(row.getUserId());
            if (user != null) {
                vo.setNickname(user.getNickname());
                vo.setAvatar(user.getAvatar());
            }
            vo.setSongId(row.getSongId());
            vo.setSongName(row.getSongName());
            vo.setArtistName(row.getArtistName());
            vo.setCover(row.getCover());
            vo.setDuration(row.getDuration());
            vo.setQueueType(row.getQueueType());
            vo.setStatus(row.getStatus());
            vo.setSeq(row.getSeq());
            vo.setSource(row.getSource());
            vo.setPriority(row.getPriority());
            vo.setVoteCount(row.getVoteCount());
            vo.setOrderIndex(row.getOrderIndex());
            vo.setCreateTime(row.getCreateTime());
            result.add(vo);
        }
        return result;
    }

    private List<QueueItemVO> queueVOs(Long roomId, Map<Long, User> users) {
        List<RoomQueue> rows = queueMapper.selectList(new QueryWrapper<RoomQueue>()
                .eq("room_id", roomId)
                .eq("queue_type", RoomQueue.TYPE_PLAY)
                .in("status", RoomQueue.STATUS_PENDING, RoomQueue.STATUS_APPROVED)
                .orderByDesc("order_index")
                .orderByAsc("seq")
                .last("LIMIT " + QUEUE_LIMIT));
        Map<Long, User> resolved = users;
        if (resolved.isEmpty()) {
            Set<Long> ids = new HashSet<>();
            for (RoomQueue row : rows) {
                ids.add(row.getUserId());
            }
            resolved = users(ids);
        }
        List<QueueItemVO> result = new ArrayList<>(rows.size());
        for (RoomQueue row : rows) {
            QueueItemVO vo = new QueueItemVO();
            vo.setId(row.getId());
            vo.setRoomId(row.getRoomId());
            vo.setUserId(row.getUserId());
            User user = resolved.get(row.getUserId());
            if (user != null) {
                vo.setNickname(user.getNickname());
                vo.setAvatar(user.getAvatar());
            }
            vo.setSongId(row.getSongId());
            vo.setSongName(row.getSongName());
            vo.setArtistName(row.getArtistName());
            vo.setCover(row.getCover());
            vo.setDuration(row.getDuration());
            vo.setQueueType(row.getQueueType());
            vo.setStatus(row.getStatus());
            vo.setSeq(row.getSeq());
            vo.setSource(row.getSource());
            vo.setPriority(row.getPriority());
            vo.setVoteCount(row.getVoteCount());
            vo.setOrderIndex(row.getOrderIndex());
            vo.setCreateTime(row.getCreateTime());
            result.add(vo);
        }
        return result;
    }

    public List<QueueItemVO> micQueue(Long roomId) {
        List<RoomQueue> rows = queueMapper.selectList(new QueryWrapper<RoomQueue>()
                .eq("room_id", roomId)
                .eq("queue_type", RoomQueue.TYPE_MIC)
                .in("status", RoomQueue.STATUS_PENDING, RoomQueue.STATUS_APPROVED)
                .orderByAsc("seq")
                .last("LIMIT 50"));
        Set<Long> ids = new HashSet<>();
        for (RoomQueue row : rows) {
            ids.add(row.getUserId());
        }
        Map<Long, User> users = users(ids);
        List<QueueItemVO> result = new ArrayList<>(rows.size());
        for (RoomQueue row : rows) {
            QueueItemVO vo = new QueueItemVO();
            vo.setId(row.getId());
            vo.setRoomId(row.getRoomId());
            vo.setUserId(row.getUserId());
            User user = users.get(row.getUserId());
            if (user != null) {
                vo.setNickname(user.getNickname());
                vo.setAvatar(user.getAvatar());
            }
            vo.setQueueType(row.getQueueType());
            vo.setStatus(row.getStatus());
            vo.setSeq(row.getSeq());
            vo.setSource(row.getSource());
            vo.setCreateTime(row.getCreateTime());
            result.add(vo);
        }
        return result;
    }

    // ------------------------------------------------------------------ WS 快照

    /**
     * {@code room.join.ack} 的 data。
     *
     * <p>保留模块 04 的字段（{@code snapshot}、{@code onlineCount}），
     * 追加模块 05 的房间快照；老客户端忽略新字段即可继续工作。
     */
    public Map<String, Object> wsSnapshot(Room room, Long userId, String nickname) {
        RoomDetailVO detail = detail(room, userId);
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("roomId", String.valueOf(room.getId()));
        ack.put("userId", String.valueOf(userId));
        ack.put("nickname", nickname);
        ack.put("ownerId", String.valueOf(room.getOwnerId()));
        ack.put("isOwner", room.getOwnerId() != null && room.getOwnerId().equals(userId));
        ack.put("onlineCount", detail.getOnlineCount());
        Map<String, Object> playState = presence.playSnapshot(room.getId());
        ack.put("snapshot", playState);
        // 模块 13 的显式别名，避免新客户端去猜 snapshot 的含义
        ack.put("playState", playState);
        ack.put("room", detail);
        ack.put("members", detail.getMembers());
        ack.put("mics", detail.getMics());
        ack.put("queue", detail.getQueue());
        ack.put("myRole", detail.getMyRole());
        ack.put("myPermissions", detail.getMyPermissions());
        ack.put("myMuteRemainingSeconds", detail.getMyMuteRemainingSeconds());
        ack.put("kickedRemainingSeconds", detail.getKickedRemainingSeconds());
        ack.put("serverTime", System.currentTimeMillis());
        return ack;
    }

    // ------------------------------------------------------------------ helpers

    private Map<Long, User> users(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, User> result = new HashMap<>();
        for (User user : userService.listByIds(ids)) {
            result.put(user.getId(), user);
        }
        return result;
    }

    private Map<Long, Song> songs(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Song> result = new HashMap<>();
        for (Song song : songService.listByIdsOrdered(ids)) {
            result.put(song.getId(), song);
        }
        return result;
    }

    private Song firstSong(Long songId) {
        List<Song> songs = songService.listByIdsOrdered(Collections.singletonList(songId));
        return songs.isEmpty() ? null : songs.get(0);
    }

    private static Long parseLong(String value) {
        return parseLong(value, null);
    }

    private static Long parseLong(String value, Long fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 供 Controller 复用：权限码集合的稳定顺序。 */
    public Set<String> permissions(Room room, int role) {
        return new LinkedHashSet<>(permissionService.permissions(room, role));
    }
}
