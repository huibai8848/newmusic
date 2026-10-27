package com.example.newmusic.module.room.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.newmusic.module.room.entity.Room;
import com.example.newmusic.module.room.entity.RoomMember;
import com.example.newmusic.module.room.mapper.RoomMapper;
import com.example.newmusic.module.room.mapper.RoomMemberMapper;
import com.example.newmusic.module.ws.service.WsPresenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 房间生命周期定时任务（PRD 14 §3 / FR-14-010）。
 *
 * <p>四件事各自独立 try/catch：热度算不出来不该影响“空房关闭”，
 * 关闭失败也不该阻止下一轮清理。任务在多实例上会并发执行，但所有写操作都是
 * “条件更新 + 幂等状态迁移”，重复执行不会产生第二种结果。
 */
@Slf4j
@Component
public class RoomLifecycleJob {

    /** 空房 30 分钟后关闭（BR-14-025） */
    private static final long EMPTY_CLOSE_MINUTES = 30;
    /** 关闭 24 小时后自动解散（BR-14-002） */
    private static final long CLOSED_DISSOLVE_HOURS = 24;
    /** 无活跃 7 天自动解散（BR-14-001） */
    private static final long INACTIVE_DISSOLVE_DAYS = 7;
    /** 单轮扫描上限，避免一个定时任务扫全表 */
    private static final int BATCH = 500;

    private final RoomMapper roomMapper;
    private final RoomMemberMapper memberMapper;
    private final RoomStateService stateService;
    private final WsPresenceService presence;
    private final RoomNotifier notifier;

    public RoomLifecycleJob(RoomMapper roomMapper, RoomMemberMapper memberMapper,
                            RoomStateService stateService, WsPresenceService presence,
                            RoomNotifier notifier) {
        this.roomMapper = roomMapper;
        this.memberMapper = memberMapper;
        this.stateService = stateService;
        this.presence = presence;
        this.notifier = notifier;
    }

    @Scheduled(fixedDelayString = "${newmusic.room.lifecycle-interval-ms:60000}",
            initialDelayString = "${newmusic.room.lifecycle-initial-delay-ms:60000}")
    public void tick() {
        safely("热度重算", this::recomputeHot);
        safely("空房关闭", this::closeEmptyRooms);
        safely("关闭超时解散", this::dissolveClosedRooms);
        safely("无活跃解散", this::dissolveInactiveRooms);
        safely("禁言到期清理", this::cleanExpiredMutes);
    }

    /**
     * 热度 = 在线人数 × 0.5 + 互动数 × 0.3 + 人均停留分钟 × 0.2（BR-14-072）。
     *
     * <p>“停留时长”取**当前在线者的人均累计停留**，而不是房间累计总时长：
     * 总时长随房间年龄单调增长，会让老房间永远压住新房间，与“热度”的产品含义相反。
     */
    private void recomputeHot() {
        List<Room> rooms = roomMapper.selectList(new QueryWrapper<Room>()
                .eq("status", Room.STATUS_ACTIVE)
                .orderByDesc("last_active_time")
                .last("LIMIT " + BATCH));
        for (Room room : rooms) {
            try {
                int online = presence.onlineCount(room.getId());
                long interactions = stateService.interactionCount(room.getId());
                long total = room.getTotalDuration() == null ? 0L : room.getTotalDuration();
                double avgMinutes = online <= 0 ? 0 : Math.min(total / 60.0 / online, 120.0);
                int score = (int) Math.round(online * 0.5 + interactions * 0.3 + avgMinutes * 0.2);
                if (room.getHotScore() == null || room.getHotScore() != score) {
                    roomMapper.updateHotScore(room.getId(), score);
                }
                stateService.setHotScore(room.getId(), score);
            } catch (Exception e) {
                log.warn("热度重算失败 roomId={} cause={}", room.getId(), e.toString());
            }
        }
    }

    private void closeEmptyRooms() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(EMPTY_CLOSE_MINUTES);
        List<Room> candidates = roomMapper.selectList(new QueryWrapper<Room>()
                .eq("status", Room.STATUS_ACTIVE)
                .eq("online_count", 0)
                .lt("last_active_time", deadline)
                .last("LIMIT " + BATCH));
        for (Room room : candidates) {
            // DB 的 online_count 只是镜像；以 Redis 的实时集合为准，避免误关有人但镜像过期的房
            if (presence.onlineCount(room.getId()) > 0) {
                roomMapper.updateOnline(room.getId(), presence.onlineCount(room.getId()),
                        LocalDateTime.now());
                continue;
            }
            room.setStatus(Room.STATUS_CLOSED);
            room.setOnlineCount(0);
            roomMapper.updateById(room);
            notifier.notifyUser(room.getOwnerId(), null, null, "房间已自动关闭",
                    "「" + room.getName() + "」超过 30 分钟无人在线，已自动关闭；24 小时内可重开",
                    room);
            log.info("空房自动关闭 roomId={} roomNo={}", room.getId(), room.getRoomNo());
        }
    }

    private void dissolveClosedRooms() {
        LocalDateTime deadline = LocalDateTime.now().minusHours(CLOSED_DISSOLVE_HOURS);
        List<Room> candidates = roomMapper.selectList(new QueryWrapper<Room>()
                .eq("status", Room.STATUS_CLOSED)
                .lt("update_time", deadline)
                .last("LIMIT " + BATCH));
        for (Room room : candidates) {
            dissolve(room, "关闭超过 24 小时，房间已自动解散");
        }
    }

    private void dissolveInactiveRooms() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(INACTIVE_DISSOLVE_DAYS);
        List<Room> candidates = roomMapper.selectList(new QueryWrapper<Room>()
                .eq("status", Room.STATUS_ACTIVE)
                .eq("online_count", 0)
                .lt("last_active_time", deadline)
                .last("LIMIT " + BATCH));
        for (Room room : candidates) {
            if (presence.onlineCount(room.getId()) > 0) {
                continue;
            }
            dissolve(room, "房间超过 7 天无活跃，已自动解散");
        }
    }

    private void dissolve(Room room, String reason) {
        List<Long> online = new ArrayList<>(stateService.onlineUserIds(room.getId()));
        room.setStatus(Room.STATUS_DISSOLVED);
        room.setOnlineCount(0);
        roomMapper.updateById(room);
        stateService.clearRoom(room.getId(), online);
        notifier.notifyUser(room.getOwnerId(), null, null, "房间已自动解散", reason, room);
        log.info("房间自动解散 roomId={} roomNo={} reason={}",
                room.getId(), room.getRoomNo(), reason);
    }

    private void cleanExpiredMutes() {
        LocalDateTime now = LocalDateTime.now();
        List<Room> rooms = roomMapper.selectList(new QueryWrapper<Room>()
                .eq("status", Room.STATUS_ACTIVE)
                .orderByDesc("last_active_time")
                .last("LIMIT " + BATCH));
        for (Room room : rooms) {
            stateService.cleanExpiredMutes(room.getId());
            try {
                memberMapper.update(null, new UpdateWrapper<RoomMember>()
                        .eq("room_id", room.getId())
                        .eq("mute_status", 1)
                        .lt("mute_until", now)
                        .set("mute_status", 0)
                        .set("mute_until", null));
            } catch (Exception e) {
                log.warn("禁言到期清理失败 roomId={} cause={}", room.getId(), e.toString());
            }
        }
    }

    private void safely(String name, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("房间生命周期任务失败 task={} cause={}", name, e.toString());
        }
    }
}
