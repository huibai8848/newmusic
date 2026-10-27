package com.example.newmusic.module.ws.service;

import com.example.newmusic.module.ws.entity.RoomMessage;
import com.example.newmusic.module.ws.mapper.RoomMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 房间消息落库与断线补偿（FR-13-003 步骤 5、BR-13-037）。
 *
 * <p><b>落库失败不影响广播</b>（EX-13-003-6）：调用方在异步线程里调用本类，
 * 异常在这里吞掉并记 ERROR。丢一条历史消息的代价，小于让一个线程池任务把
 * 整个房间的实时聊天卡住。
 */
@Slf4j
@Service
public class RoomMessageService {

    /** 单次补偿上限，防止客户端用一个很旧的游标拉出一条长尾 */
    public static final int MAX_SINCE_LIMIT = 200;

    private final RoomMessageMapper roomMessageMapper;

    public RoomMessageService(RoomMessageMapper roomMessageMapper) {
        this.roomMessageMapper = roomMessageMapper;
    }

    /** 以 WS 广播用的同一个 ID 落库；失败返回 false（只统计，不抛）。 */
    public boolean append(long messageId, long roomId, long userId, int messageType,
                          String content, String extra, int auditStatus) {
        RoomMessage message = new RoomMessage();
        message.setId(messageId);
        message.setRoomId(roomId);
        message.setUserId(userId);
        message.setMessageType(messageType);
        message.setContent(content);
        message.setExtra(extra);
        message.setAuditStatus(auditStatus);
        message.setStatus(1);
        message.setCreateTime(LocalDateTime.now());
        try {
            roomMessageMapper.insert(message);
            return true;
        } catch (Exception e) {
            log.error("房间消息落库失败（广播不受影响） messageId={} roomId={} cause={}",
                    messageId, roomId, e.toString());
            return false;
        }
    }

    public List<RoomMessage> listSince(Long roomId, long sinceId, int limit) {
        if (roomId == null) {
            return Collections.emptyList();
        }
        int effective = Math.min(Math.max(limit, 1), MAX_SINCE_LIMIT);
        return roomMessageMapper.listSince(roomId, Math.max(sinceId, 0L), effective);
    }
}
