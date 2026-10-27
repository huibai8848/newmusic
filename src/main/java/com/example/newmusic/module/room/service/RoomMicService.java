package com.example.newmusic.module.room.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.lock.DistributedLockService;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.module.room.dto.MicLockReq;
import com.example.newmusic.module.room.entity.Room;
import com.example.newmusic.module.room.entity.RoomMic;
import com.example.newmusic.module.room.entity.RoomQueue;
import com.example.newmusic.module.room.mapper.RoomMicMapper;
import com.example.newmusic.module.room.mapper.RoomQueueMapper;
import com.example.newmusic.module.room.vo.MicVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 麦位与排麦（FR-14-005）。
 *
 * <p><b>并发模型</b>：所有会改变“哪个麦位属于谁”的操作都在
 * {@code ml:lock:room:mic:{roomId}} 下串行执行；锁内再用
 * {@code UPDATE ... WHERE status = 0} 做一次 CAS。两层不是重复：
 * 锁挡住的是同一房间的并发请求，CAS 挡住的是“锁过期/Redis 降级时的进程内并发”。
 *
 * <p><b>双写顺序</b>：先 DB（{@code t_room_mic} 是权威）再 Redis 镜像。
 * 反过来会出现“Redis 说这个位置有人、DB 里没人”，重启后麦位凭空消失。
 */
@Slf4j
@Service
public class RoomMicService {

    /** BR-14-033：排麦队列上限 50 */
    private static final int MIC_QUEUE_LIMIT = 50;

    private final RoomPermissionService permissionService;
    private final RoomStateService stateService;
    private final RoomMicMapper micMapper;
    private final RoomQueueMapper queueMapper;
    private final RoomAssembler assembler;
    private final RoomNotifier notifier;
    private final DistributedLockService lockService;

    public RoomMicService(RoomPermissionService permissionService, RoomStateService stateService,
                          RoomMicMapper micMapper, RoomQueueMapper queueMapper,
                          RoomAssembler assembler, RoomNotifier notifier,
                          DistributedLockService lockService) {
        this.permissionService = permissionService;
        this.stateService = stateService;
        this.micMapper = micMapper;
        this.queueMapper = queueMapper;
        this.assembler = assembler;
        this.notifier = notifier;
        this.lockService = lockService;
    }

    // ------------------------------------------------------------------ 申请 / 下麦

    /**
     * 申请上麦。
     *
     * <p>三种结果：已经在麦上（幂等成功）、直接上麦（自由上麦/无审核且有位置）、
     * 进入排麦队列。返回值明确区分它们，前端不需要再猜。
     */
    @Transactional
    public Map<String, Object> apply(Long roomId, Long userId) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireMember(roomId, userId);
        if (permissionService.isMuted(room, userId)) {
            throw new BizException(ResultCode.ROOM_MUTED);
        }
        if (permissionService.isBlacklisted(room, userId)) {
            throw new BizException(ResultCode.ROOM_BLACKLISTED);
        }

        RoomMic current = micOf(roomId, userId);
        if (current != null) {
            return result(true, current.getMicPos(), 0);
        }
        RoomQueue pending = activeMicQueue(roomId, userId);
        if (pending != null) {
            throw new BizException(ResultCode.ROOM_QUEUE_DUPLICATED);
        }
        if (stateService.inMicQueue(roomId, userId)) {
            throw new BizException(ResultCode.ROOM_QUEUE_DUPLICATED);
        }

        boolean auto = Integer.valueOf(0).equals(room.getNeedApprove())
                || Integer.valueOf(1).equals(room.getAllowFreeMic());
        if (auto) {
            Integer pos = assignFirstFree(room, userId);
            if (pos != null) {
                markQueuePlayed(roomId, userId);
                broadcast(roomId);
                return result(true, pos, 0);
            }
        }
        if (stateService.micQueueSize(roomId) >= MIC_QUEUE_LIMIT) {
            throw new BizException(ResultCode.ROOM_MIC_QUEUE_FULL);
        }

        long seq = stateService.nextQueueSeq(roomId);
        RoomQueue queue = new RoomQueue();
        queue.setRoomId(roomId);
        queue.setUserId(userId);
        queue.setQueueType(RoomQueue.TYPE_MIC);
        queue.setStatus(auto ? RoomQueue.STATUS_APPROVED : RoomQueue.STATUS_PENDING);
        queue.setSeq(seq);
        queue.setSource(RoomQueue.SOURCE_MIC);
        queueMapper.insert(queue);
        stateService.enqueueMic(roomId, userId, seq);
        broadcast(roomId);

        Map<String, Object> data = result(false, null, stateService.micQueueSize(roomId));
        data.put("queueId", String.valueOf(queue.getId()));
        data.put("status", queue.getStatus());
        return data;
    }

    /** 下麦；重复调用幂等。 */
    @Transactional
    public void leave(Long roomId, Long userId) {
        Room room = permissionService.requireRoom(roomId);
        release(room, userId);
        broadcast(roomId);
    }

    /** 离开房间 / 被踢 / 被禁言时的强制下麦，不做权限校验。 */
    public void releaseOnLeave(Long roomId, Long userId) {
        Room room = permissionService.requireRoom(roomId);
        int released = release(room, userId);
        if (released > 0) {
            broadcast(roomId);
        }
    }

    private int release(Room room, Long userId) {
        List<RoomMic> occupied = micMapper.selectList(new QueryWrapper<RoomMic>()
                .eq("room_id", room.getId()).eq("user_id", userId));
        if (occupied.isEmpty()) {
            stateService.dequeueMic(room.getId(), userId);
            return 0;
        }
        for (RoomMic mic : occupied) {
            stateService.releaseMic(room.getId(), mic.getMicPos());
        }
        micMapper.releaseByUser(room.getId(), userId);
        stateService.dequeueMic(room.getId(), userId);

        RoomQueue queue = activeMicQueue(room.getId(), userId);
        if (queue != null) {
            queue.setStatus(RoomQueue.STATUS_PLAYED);
            queueMapper.updateById(queue);
        }
        return occupied.size();
    }

    // ------------------------------------------------------------------ 审批

    @Transactional
    public void approve(Long roomId, Long operatorId, Long targetUserId, boolean approve) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireAdminOrOwner(room, operatorId);
        if (targetUserId == null) {
            throw new BizException(ResultCode.PARAM_INVALID);
        }
        RoomQueue queue = activeMicQueue(roomId, targetUserId);
        if (queue == null) {
            // 已经上麦：批准幂等返回；拒绝则走强制下麦
            if (approve) {
                if (micOf(roomId, targetUserId) != null) {
                    return;
                }
                throw new BizException(ResultCode.PARAM_INVALID, "该用户不在排麦队列中");
            }
            release(room, targetUserId);
            notifier.userEvent(targetUserId, "room.mic.rejected",
                    single("roomId", String.valueOf(roomId)));
            broadcast(roomId);
            return;
        }
        if (!approve) {
            queue.setStatus(RoomQueue.STATUS_REJECTED);
            queueMapper.updateById(queue);
            stateService.dequeueMic(roomId, targetUserId);
            notifier.userEvent(targetUserId, "room.mic.rejected",
                    single("roomId", String.valueOf(roomId)));
            broadcast(roomId);
            return;
        }

        Integer pos = assignFirstFree(room, targetUserId);
        if (pos == null) {
            throw new BizException(ResultCode.ROOM_MIC_FULL);
        }
        queue.setStatus(RoomQueue.STATUS_APPROVED);
        queueMapper.updateById(queue);
        stateService.dequeueMic(roomId, targetUserId);
        notifier.userEvent(targetUserId, "room.mic.approved", data(roomId, pos));
        broadcast(roomId);
    }

    // ------------------------------------------------------------------ 锁定

    @Transactional
    public void lock(Long roomId, Long operatorId, MicLockReq req) {
        Room room = permissionService.requireActiveForOperation(roomId);
        permissionService.requireAdminOrOwner(room, operatorId);
        int pos = req.getMicPos() == null ? 0 : req.getMicPos();
        if (pos < 1 || pos > safeMicCount(room)) {
            throw new BizException(ResultCode.PARAM_INVALID, "麦位号超出范围");
        }
        if (pos == 1 && !permissionService.isOwner(room, operatorId)) {
            // 1 号麦是房主专属位，房管也不能锁
            throw new BizException(ResultCode.ROOM_NO_PERMISSION);
        }
        boolean locked = Boolean.TRUE.equals(req.getLocked());
        int updated = micMapper.updateLock(roomId, pos,
                locked ? RoomMic.STATUS_LOCKED : RoomMic.STATUS_EMPTY);
        if (updated == 0) {
            throw new BizException(ResultCode.ROOM_STATUS_ABNORMAL, "该麦位正在使用中，无法锁定");
        }
        if (locked) {
            stateService.releaseMic(roomId, pos);
        }
        broadcast(roomId);
    }

    // ------------------------------------------------------------------ 查询

    public List<MicVO> mics(Long roomId) {
        return assembler.mics(roomId);
    }

    public List<com.example.newmusic.module.room.vo.QueueItemVO> micQueue(Long roomId) {
        return assembler.micQueue(roomId);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 在分布式锁内找一个空麦位并原子占用。
     *
     * @return 分配到的麦位号；无可用麦位返回 null
     */
    private Integer assignFirstFree(Room room, Long userId) {
        return lockService.executeOrFail(RedisKeys.lockRoomMic(room.getId()), () -> {
            if (micOf(room.getId(), userId) != null) {
                throw new BizException(ResultCode.ROOM_QUEUE_DUPLICATED);
            }
            List<RoomMic> mics = new ArrayList<>(micMapper.selectList(new QueryWrapper<RoomMic>()
                    .eq("room_id", room.getId()).orderByAsc("mic_pos")));
            for (RoomMic mic : mics) {
                if (mic.getMicPos() == null) {
                    continue;
                }
                if (!Integer.valueOf(RoomMic.STATUS_EMPTY).equals(mic.getStatus())) {
                    continue;
                }
                if (mic.getMicPos() == 1 && !permissionService.isOwner(room, userId)) {
                    continue;
                }
                if (micMapper.occupy(mic.getId(), userId) == 1) {
                    stateService.occupyMic(room.getId(), mic.getMicPos(), userId);
                    return mic.getMicPos();
                }
            }
            return null;
        });
    }

    private RoomMic micOf(Long roomId, Long userId) {
        return micMapper.selectOne(new QueryWrapper<RoomMic>()
                .eq("room_id", roomId).eq("user_id", userId)
                .orderByAsc("mic_pos").last("LIMIT 1"));
    }

    private RoomQueue activeMicQueue(Long roomId, Long userId) {
        return queueMapper.selectOne(new QueryWrapper<RoomQueue>()
                .eq("room_id", roomId).eq("user_id", userId)
                .eq("queue_type", RoomQueue.TYPE_MIC)
                .in("status", RoomQueue.STATUS_PENDING, RoomQueue.STATUS_APPROVED)
                .orderByDesc("seq").last("LIMIT 1"));
    }

    private void markQueuePlayed(Long roomId, Long userId) {
        RoomQueue queue = activeMicQueue(roomId, userId);
        if (queue != null) {
            queue.setStatus(RoomQueue.STATUS_PLAYED);
            queueMapper.updateById(queue);
            stateService.dequeueMic(roomId, userId);
        }
    }

    /** 广播麦位与排麦队列（PRD 的 room.mic.update / room.queue.update）。 */
    private void broadcast(Long roomId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roomId", String.valueOf(roomId));
        data.put("mics", assembler.mics(roomId));
        data.put("micQueue", assembler.micQueue(roomId));
        data.put("onlineCount", stateService.onlineUserIds(roomId).size());
        notifier.roomEvent(roomId, "room.mic.update", data);
        notifier.roomEvent(roomId, "room.queue.update", data);
    }

    private Map<String, Object> result(boolean onMic, Integer micPos, int queuePosition) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("onMic", onMic);
        data.put("micPos", micPos);
        data.put("queuePosition", queuePosition);
        return data;
    }

    private Map<String, Object> data(Long roomId, Integer micPos) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roomId", String.valueOf(roomId));
        data.put("micPos", micPos);
        return data;
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static int safeMicCount(Room room) {
        return room.getMicCount() == null || room.getMicCount() < 1 ? 6 : room.getMicCount();
    }
}
