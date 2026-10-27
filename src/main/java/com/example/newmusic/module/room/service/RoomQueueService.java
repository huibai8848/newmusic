package com.example.newmusic.module.room.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.service.SongService;
import com.example.newmusic.module.room.entity.Room;
import com.example.newmusic.module.room.entity.RoomQueue;
import com.example.newmusic.module.room.mapper.RoomQueueMapper;
import com.example.newmusic.module.room.vo.QueueItemVO;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 播放队列（FR-14-007）。
 *
 * <p>DB 是权威，Redis 的 {@code ml:room:queue:{roomId}} 只是**已通过**队列的快照，
 * 供实时播放路径读取。不要把待审项写进快照：它会被当作可播放歌曲。
 *
 * <p>上限都来自 PRD：队列 100 首（BR-14-048）、每人同时 3 首（BR-14-049）。
 */
@Slf4j
@Service
public class RoomQueueService {

    /** 模块 13 BR-22-023：队列上限 50 首（模块 05 原为 100）。 */
    private static final int QUEUE_LIMIT = 50;
    private static final int USER_QUEUE_LIMIT = 3;
    /** 房主置顶优先级（BR-22-024）。 */
    private static final int PRIORITY_OWNER = 100;

    private final RoomPermissionService permissionService;
    private final RoomStateService stateService;
    private final RoomQueueMapper queueMapper;
    private final SongService songService;
    private final RoomAssembler assembler;
    private final RoomNotifier notifier;
    private final UserService userService;
    private final RoomRankService rankService;

    public RoomQueueService(RoomPermissionService permissionService, RoomStateService stateService,
                            RoomQueueMapper queueMapper, SongService songService,
                            RoomAssembler assembler, RoomNotifier notifier,
                            UserService userService, RoomRankService rankService) {
        this.permissionService = permissionService;
        this.stateService = stateService;
        this.queueMapper = queueMapper;
        this.songService = songService;
        this.assembler = assembler;
        this.notifier = notifier;
        this.userService = userService;
        this.rankService = rankService;
    }

    // ------------------------------------------------------------------ 点歌

    @Transactional
    public QueueItemVO add(Long roomId, Long userId, Long songId) {
        if (songId == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "缺少 songId");
        }
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireMember(roomId, userId);
        if (permissionService.isBlacklisted(room, userId)) {
            throw new BizException(ResultCode.ROOM_BLACKLISTED);
        }
        // requirePlayable 同时校验存在/上架/审核/版权，错误码 30001/30002/30008
        Song song = songService.requirePlayable(songId);

        // BR-22-025：同一首歌已在队列时合并（投票数 +1），不重复入队。
        // 合并放在上限判断之前：它不是一次新的排队，不应被「个人 3 首」挡住。
        RoomQueue existing = findActiveBySong(roomId, songId);
        if (existing != null) {
            int votes = (existing.getVoteCount() == null ? 0 : existing.getVoteCount()) + 1;
            existing.setVoteCount(votes);
            existing.setOrderIndex(orderIndex(existing.getPriority(), votes, existing.getSeq()));
            queueMapper.updateById(existing);
            refresh(roomId);
            queueUpdate(roomId, "merged", existing);
            notifier.system(roomId, userId,
                    nickname(userId) + " 再次点了《" + song.getName() + "》，合并投票 +1");
            rankService.recordRequest(roomId, userId);
            return assembler.queueItem(existing);
        }

        if (activeCount(roomId, null) >= QUEUE_LIMIT) {
            throw new BizException(ResultCode.ROOM_QUEUE_FULL);
        }
        if (activeCount(roomId, userId) >= USER_QUEUE_LIMIT) {
            throw new BizException(ResultCode.ROOM_USER_QUEUE_LIMIT);
        }

        boolean privileged = permissionService.canControl(room, userId);
        boolean needApprove = Integer.valueOf(1).equals(room.getNeedApprove());
        long seq = stateService.nextQueueSeq(roomId);
        int priority = permissionService.isOwner(room, userId) ? PRIORITY_OWNER : 0;
        RoomQueue queue = new RoomQueue();
        queue.setRoomId(roomId);
        queue.setUserId(userId);
        queue.setSongId(song.getId());
        queue.setSongName(song.getName());
        queue.setArtistName(song.getArtistName());
        queue.setCover(song.getCover());
        queue.setDuration(song.getDuration());
        queue.setQueueType(RoomQueue.TYPE_PLAY);
        queue.setStatus(privileged || !needApprove
                ? RoomQueue.STATUS_APPROVED : RoomQueue.STATUS_PENDING);
        queue.setSeq(seq);
        queue.setSource(privileged ? RoomQueue.SOURCE_OWNER : RoomQueue.SOURCE_MEMBER);
        queue.setPriority(priority);
        queue.setVoteCount(0);
        queue.setOrderIndex(orderIndex(priority, 0, seq));
        queueMapper.insert(queue);

        refresh(roomId);
        queueUpdate(roomId, "added", queue);
        if (queue.getStatus() == RoomQueue.STATUS_PENDING) {
            notifier.roomEvent(roomId, "room.queue.pending", single("queueId", queue.getId()));
        }
        notifier.system(roomId, userId,
                nickname(userId) + " 点了《" + song.getName() + "》");
        rankService.recordRequest(roomId, userId);
        return assembler.queueItem(queue);
    }

    // ------------------------------------------------------------------ 房主置顶 / 撤销

    /** 房主置顶/取消置顶（FR-22-003 BR-22-024）。 */
    @Transactional
    public QueueItemVO pin(Long roomId, Long operatorId, Long queueId, boolean pinned) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireOwner(room, operatorId);
        RoomQueue queue = requireQueue(roomId, queueId);
        if (Integer.valueOf(RoomQueue.STATUS_PLAYED).equals(queue.getStatus())
                || Integer.valueOf(RoomQueue.STATUS_REMOVED).equals(queue.getStatus())
                || Integer.valueOf(RoomQueue.STATUS_REJECTED).equals(queue.getStatus())) {
            throw new BizException(ResultCode.ROOM_STATUS_ABNORMAL, "该点歌已结束，无法调整优先级");
        }
        queue.setPriority(pinned ? PRIORITY_OWNER : 0);
        queue.setOrderIndex(orderIndex(queue.getPriority(), queue.getVoteCount(), queue.getSeq()));
        queueMapper.updateById(queue);
        refresh(roomId);
        queueUpdate(roomId, "priority", queue);
        return assembler.queueItem(queue);
    }

    /** 点歌人撤销自己的点歌（BR-22-028）；房主/房管的删除权限见 {@link #remove}。 */
    @Transactional
    public void withdraw(Long roomId, Long userId, Long queueId) {
        permissionService.requireActiveForOperation(roomId);
        RoomQueue queue = requireQueue(roomId, queueId);
        if (!userId.equals(queue.getUserId())) {
            throw new BizException(ResultCode.ROOM_NO_PERMISSION, "只能撤销自己点的歌");
        }
        if (!Integer.valueOf(RoomQueue.STATUS_PENDING).equals(queue.getStatus())
                && !Integer.valueOf(RoomQueue.STATUS_APPROVED).equals(queue.getStatus())) {
            throw new BizException(ResultCode.ROOM_STATUS_ABNORMAL, "该点歌已无法撤销");
        }
        queue.setStatus(RoomQueue.STATUS_REMOVED);
        queueMapper.updateById(queue);
        refresh(roomId);
        queueUpdate(roomId, "removed", queue);
    }

    // ------------------------------------------------------------------ 审批 / 删除

    @Transactional
    public void approve(Long roomId, Long operatorId, Long queueId, boolean approve) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireAdminOrOwner(room, operatorId);
        RoomQueue queue = requireQueue(roomId, queueId);
        if (!Integer.valueOf(RoomQueue.STATUS_PENDING).equals(queue.getStatus())) {
            throw new BizException(ResultCode.ROOM_STATUS_ABNORMAL, "该点歌已处理");
        }
        queue.setStatus(approve ? RoomQueue.STATUS_APPROVED : RoomQueue.STATUS_REJECTED);
        queueMapper.updateById(queue);
        refresh(roomId);
        queueUpdate(roomId, approve ? "approved" : "rejected", queue);
        notifier.userEvent(queue.getUserId(), "room.queue.approved", data(roomId, queue, approve));
    }

    @Transactional
    public void remove(Long roomId, Long operatorId, Long queueId) {
        Room room = permissionService.requireActiveForOperation(roomId);
        RoomQueue queue = requireQueue(roomId, queueId);
        boolean self = operatorId != null && operatorId.equals(queue.getUserId())
                && (Integer.valueOf(RoomQueue.STATUS_PENDING).equals(queue.getStatus())
                || Integer.valueOf(RoomQueue.STATUS_APPROVED).equals(queue.getStatus()));
        if (!self) {
            permissionService.requireAdminOrOwner(room, operatorId);
        }
        if (Integer.valueOf(RoomQueue.STATUS_REMOVED).equals(queue.getStatus())) {
            return;
        }
        queue.setStatus(RoomQueue.STATUS_REMOVED);
        queueMapper.updateById(queue);
        refresh(roomId);
        queueUpdate(roomId, "removed", queue);
        notifier.userEvent(queue.getUserId(), "room.queue.removed", data(roomId, queue, false));
    }

    @Transactional
    public int clear(Long roomId, Long operatorId) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireAdminOrOwner(room, operatorId);
        List<RoomQueue> rows = activeRows(roomId);
        for (RoomQueue row : rows) {
            row.setStatus(RoomQueue.STATUS_REMOVED);
            queueMapper.updateById(row);
        }
        refresh(roomId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roomId", String.valueOf(roomId));
        payload.put("action", "cleared");
        notifier.roomEvent(roomId, "ROOM_QUEUE_UPDATE", payload);
        notifier.system(roomId, operatorId, "房主清空了播放队列");
        return rows.size();
    }

    // ------------------------------------------------------------------ 查询 / 播放联动

    public List<QueueItemVO> list(Long roomId) {
        permissionService.requireRoom(roomId);
        return assembler.queue(roomId);
    }

    /**
     * 切歌后把旧歌在队列里标记为已播放。
     *
     * <p>只匹配**最近一条**同 songId 的已通过记录：同一首歌可以被不同用户重复点，
     * 全量更新会把还没播到的重复点歌一起吞掉。
     */
    @Transactional
    public void markPlayed(Long roomId, Long songId) {
        changeStatus(roomId, songId, RoomQueue.STATUS_PLAYED);
    }

    /** 投票通过/房主跳过：当前项标记为「被跳过」而不是「已播放」（BR-22-041）。 */
    @Transactional
    public void markSkipped(Long roomId, Long songId) {
        changeStatus(roomId, songId, RoomQueue.STATUS_SKIPPED);
    }

    private void changeStatus(Long roomId, Long songId, int status) {
        if (songId == null) {
            return;
        }
        RoomQueue row = queueMapper.selectOne(new QueryWrapper<RoomQueue>()
                .eq("room_id", roomId)
                .eq("queue_type", RoomQueue.TYPE_PLAY)
                .eq("song_id", songId)
                .eq("status", RoomQueue.STATUS_APPROVED)
                .orderByAsc("seq").last("LIMIT 1"));
        if (row != null) {
            row.setStatus(status);
            queueMapper.updateById(row);
            refresh(roomId);
            queueUpdate(roomId, status == RoomQueue.STATUS_SKIPPED ? "skipped" : "played", row);
        }
    }

    /** 下一条待播：按 orderIndex 降序（优先级/投票高的先播），同分按入队序号。 */
    public RoomQueue nextApproved(Long roomId) {
        return queueMapper.selectOne(new QueryWrapper<RoomQueue>()
                .eq("room_id", roomId)
                .eq("queue_type", RoomQueue.TYPE_PLAY)
                .eq("status", RoomQueue.STATUS_APPROVED)
                .orderByDesc("order_index")
                .orderByAsc("seq")
                .last("LIMIT 1"));
    }

    /** 供模块 04 的控播路径调用：切歌后同步房间当前歌曲并广播队列。 */
    public void onSongChanged(Long roomId, Long previousSongId) {
        markPlayed(roomId, previousSongId);
    }

    // ------------------------------------------------------------------ 内部

    private void refresh(Long roomId) {
        List<RoomQueue> approved = queueMapper.selectList(new QueryWrapper<RoomQueue>()
                .eq("room_id", roomId)
                .eq("queue_type", RoomQueue.TYPE_PLAY)
                .eq("status", RoomQueue.STATUS_APPROVED)
                .orderByDesc("order_index")
                .orderByAsc("seq")
                .last("LIMIT " + QUEUE_LIMIT));
        // 快照只保留已通过项：待审项不应出现在播放路径上
        stateService.saveQueueSnapshot(roomId, toVO(approved));
        // 模块 04/05 的全量事件保留（老客户端依赖），新客户端用 ROOM_QUEUE_UPDATE 增量更新
        notifier.roomEvent(roomId, "room.queue.update", queueData(roomId));
    }

    /** 增量队列事件（BR-22-029）。 */
    private void queueUpdate(Long roomId, String action, RoomQueue row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roomId", String.valueOf(roomId));
        data.put("action", action);
        data.put("item", assembler.queueItem(row));
        notifier.roomEvent(roomId, "ROOM_QUEUE_UPDATE", data);
    }

    /**
     * 排序索引：{@code priority * 10000 + voteCount * 100 + (1000 - 插入顺序)}。
     * 第 4 段取模是为了防止长房间的 seq 吃掉投票/优先级的权重。
     */
    static long orderIndex(Integer priority, Integer voteCount, Long seq) {
        long p = priority == null ? 0L : priority;
        long v = voteCount == null ? 0L : voteCount;
        long s = seq == null ? 0L : seq;
        return p * 10000L + v * 100L + (1000L - Math.floorMod(s, 1000L));
    }

    private RoomQueue findActiveBySong(Long roomId, Long songId) {
        return queueMapper.selectOne(new QueryWrapper<RoomQueue>()
                .eq("room_id", roomId)
                .eq("queue_type", RoomQueue.TYPE_PLAY)
                .eq("song_id", songId)
                .in("status", RoomQueue.STATUS_PENDING, RoomQueue.STATUS_APPROVED)
                .orderByAsc("seq")
                .last("LIMIT 1"));
    }

    private String nickname(Long userId) {
        try {
            User user = userService.getById(userId);
            return user == null || user.getNickname() == null ? "用户" : user.getNickname();
        } catch (Exception e) {
            return "用户";
        }
    }

    /** 广播数据单独构造，保持与麦位服务一致的字段名。 */
    public Map<String, Object> queueData(Long roomId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roomId", String.valueOf(roomId));
        data.put("queue", assembler.queue(roomId));
        return data;
    }

    private List<QueueItemVO> toVO(List<RoomQueue> rows) {
        List<QueueItemVO> result = new java.util.ArrayList<>(rows.size());
        for (RoomQueue row : rows) {
            result.add(assembler.queueItem(row));
        }
        return result;
    }

    private List<RoomQueue> activeRows(Long roomId) {
        return queueMapper.selectList(new QueryWrapper<RoomQueue>()
                .eq("room_id", roomId)
                .eq("queue_type", RoomQueue.TYPE_PLAY)
                .in("status", RoomQueue.STATUS_PENDING, RoomQueue.STATUS_APPROVED)
                .orderByAsc("seq"));
    }

    private long activeCount(Long roomId, Long userId) {
        QueryWrapper<RoomQueue> wrapper = new QueryWrapper<RoomQueue>()
                .eq("room_id", roomId)
                .eq("queue_type", RoomQueue.TYPE_PLAY)
                .in("status", RoomQueue.STATUS_PENDING, RoomQueue.STATUS_APPROVED);
        if (userId != null) {
            wrapper.eq("user_id", userId);
        }
        return queueMapper.selectCount(wrapper);
    }

    private RoomQueue requireQueue(Long roomId, Long queueId) {
        RoomQueue queue = queueId == null ? null : queueMapper.selectById(queueId);
        if (queue == null || !roomId.equals(queue.getRoomId())
                || !Integer.valueOf(RoomQueue.TYPE_PLAY).equals(queue.getQueueType())) {
            throw new BizException(ResultCode.PARAM_INVALID, "队列项不存在");
        }
        return queue;
    }

    private Map<String, Object> data(Long roomId, RoomQueue queue, boolean approved) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roomId", String.valueOf(roomId));
        data.put("queueId", String.valueOf(queue.getId()));
        data.put("songId", String.valueOf(queue.getSongId()));
        data.put("songName", queue.getSongName());
        data.put("approved", approved);
        return data;
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
