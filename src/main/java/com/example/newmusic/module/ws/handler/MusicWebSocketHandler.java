package com.example.newmusic.module.ws.handler;

import cn.hutool.core.util.IdUtil;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.lock.DistributedLockService;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.infra.ratelimit.RateLimitService;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.service.SongService;
import com.example.newmusic.module.room.entity.Room;
import com.example.newmusic.module.room.service.RoomPlayService;
import com.example.newmusic.module.room.service.RoomRankService;
import com.example.newmusic.module.room.service.RoomService;
import com.example.newmusic.module.room.service.RoomVoteService;
import com.example.newmusic.module.room.vo.RoomMemberVO;
import com.example.newmusic.module.room.vo.RoomVoteVO;
import com.example.newmusic.module.contentsafety.service.MinorProtectionService;
import com.example.newmusic.module.contentsafety.service.UserRestrictionService;
import com.example.newmusic.module.search.support.SensitiveWordSupport;
import com.example.newmusic.module.ws.config.WsProperties;
import com.example.newmusic.module.ws.entity.RoomMessage;
import com.example.newmusic.module.ws.service.NotificationService;
import com.example.newmusic.module.ws.service.RoomMessageService;
import com.example.newmusic.module.ws.service.WsDedupService;
import com.example.newmusic.module.ws.service.WsMetrics;
import com.example.newmusic.module.ws.service.WsPresenceService;
import com.example.newmusic.module.ws.service.WsPushService;
import com.example.newmusic.module.ws.support.WsHandshakeInterceptor;
import com.example.newmusic.module.ws.support.WsMessages;
import com.example.newmusic.module.ws.support.WsSession;
import com.example.newmusic.module.ws.support.WsSessionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 音乐平台唯一的 WebSocket 端点（{@code /ws}）。
 *
 * <p><b>职责边界</b>：只做「解析协议 → 校验 → 调用服务 → 下发」，
 * 会话表、Redis 状态、广播、落库、限流、敏感词各自在 {@code support}/{@code service} 里。
 * 这样每条消息的处理路径都能在本类一眼读完，而并发/降级细节留在各自的类里。
 *
 * <p><b>所有异常都在这里收口</b>：WS 没有 Spring MVC 的全局异常处理器，
 * 一个漏掉的异常会沿着 IO 线程抛到容器，连接直接消失且客户端只看到 1006。
 * 所以 {@link #handleTextMessage} 最外层兜底为「error 帧 + 保持连接」——
 * 单条坏消息不应该终止整条连接。
 */
@Slf4j
@Component
public class MusicWebSocketHandler extends TextWebSocketHandler {

    /** 连接属性：握手阶段写入的身份 + 连接建立后写入的会话对象 */
    static final String ATTR_SESSION = "ws.session";

    private static final int CHAT_MAX_LENGTH = 200;
    /** 模块 13 BR-22-045：弹幕 30 字上限（模块 04 原为 50）。 */
    private static final int DANMAKU_MAX_LENGTH = 30;
    private static final int RATE_WINDOW_SECONDS = 60;
    /** 快捷表情（FR-22-006）：只允许预置集合，避免把任意长文本塞进表情通道。 */
    private static final Set<String> PRESET_EMOJIS = new HashSet<>(Arrays.asList(
            "😂", "👍", "❤️", "🎉", "🔥", "👏", "😍", "😭", "🤔", "🐶"));
    /** 昵称最长 20 字；允许中英文、数字、下划线。 */
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([\\p{L}\\p{N}_]{1,20})");

    private final WsProperties properties;
    private final WsSessionRegistry registry;
    private final WsPresenceService presence;
    private final WsDedupService dedup;
    private final WsPushService push;
    private final RateLimitService rateLimit;
    private final SensitiveWordSupport sensitiveWords;
    private final SongService songService;
    private final RoomMessageService roomMessageService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final WsMetrics metrics;
    private final Executor messageExecutor;
    private final RoomService roomService;
    private final DistributedLockService lockService;
    private final RoomPlayService playService;
    private final RoomVoteService voteService;
    private final RoomRankService rankService;
    private final UserRestrictionService restrictionService;
    private final MinorProtectionService minorService;

    public MusicWebSocketHandler(WsProperties properties, WsSessionRegistry registry,
                                 WsPresenceService presence, WsDedupService dedup,
                                 WsPushService push, RateLimitService rateLimit,
                                 SensitiveWordSupport sensitiveWords, SongService songService,
                                 RoomMessageService roomMessageService,
                                 NotificationService notificationService,
                                 ObjectMapper objectMapper, WsMetrics metrics,
                                 RoomService roomService, DistributedLockService lockService,
                                 RoomPlayService playService, RoomVoteService voteService,
                                 RoomRankService rankService,
                                 UserRestrictionService restrictionService,
                                 MinorProtectionService minorService,
                                 @Qualifier("wsMessageExecutor") Executor messageExecutor) {
        this.properties = properties;
        this.registry = registry;
        this.presence = presence;
        this.dedup = dedup;
        this.push = push;
        this.rateLimit = rateLimit;
        this.sensitiveWords = sensitiveWords;
        this.songService = songService;
        this.roomMessageService = roomMessageService;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.roomService = roomService;
        this.lockService = lockService;
        this.playService = playService;
        this.voteService = voteService;
        this.rankService = rankService;
        this.restrictionService = restrictionService;
        this.minorService = minorService;
        this.messageExecutor = messageExecutor;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        Map<String, Object> attrs = socket.getAttributes();
        Long userId = asLong(attrs.get(WsHandshakeInterceptor.ATTR_USER_ID));
        if (userId == null) {
            // 握手拦截器已保证有身份；走到这里说明装配被改了，按不可信处理
            closeQuietly(socket, CloseStatus.POLICY_VIOLATION.withReason("unauthenticated"));
            return;
        }
        Long tokenExpireAt = asLong(attrs.get(WsHandshakeInterceptor.ATTR_TOKEN_EXPIRE_AT));
        WsSession ws = new WsSession(
                registry.nextConnectionId(),
                userId,
                asString(attrs.get(WsHandshakeInterceptor.ATTR_NICKNAME)),
                asString(attrs.get(WsHandshakeInterceptor.ATTR_DEVICE_ID)),
                asString(attrs.get(WsHandshakeInterceptor.ATTR_IP)),
                tokenExpireAt == null ? 0L : tokenExpireAt,
                socket, metrics);

        if (registry.register(ws) == null) {
            // 实例连接数上限（握手后再判一次，覆盖并发握手挤进来的情况）
            ws.close(CloseStatus.SERVICE_OVERLOAD.withReason("server busy"));
            return;
        }
        attrs.put(ATTR_SESSION, ws);
        presence.registerSession(ws);

        Map<String, Object> connected = new LinkedHashMap<>();
        connected.put("connectionId", ws.getConnectionId());
        connected.put("serverTime", System.currentTimeMillis());
        // PRD §8.2 示例给的是毫秒（30000），配置按秒更易读，这里换算后再下发
        connected.put("heartbeatInterval", properties.getHeartbeatIntervalSeconds() * 1000L);
        connected.put("maxMessageSize", properties.getMaxMessageSize());
        connected.put("tokenExpireAt", ws.getTokenExpireAt());
        push.sendFrame(ws, "connected", connected);

        // BR-13-027：未读数是重连后客户端恢复 UI 的必需项，连上就发
        push.sendServer(ws, "notify.unread",
                single("unread", notificationService.unreadCount(userId)));

        log.info("WS 连接建立 connectionId={} userId={} device={} current={}",
                ws.getConnectionId(), userId, ws.getDeviceId(), registry.totalConnections());
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        WsSession ws = session(socket);
        if (ws == null) {
            closeQuietly(socket, CloseStatus.POLICY_VIOLATION.withReason("unknown session"));
            return;
        }
        // 心跳也走这里，先 touch 再做任何可能失败的事
        ws.touch();
        metrics.incrementMessagesIn();

        String payload = message.getPayload();
        if (payload != null && payload.length() > properties.getMaxMessageSize()) {
            push.sendError(ws, ResultCode.PARAM_INVALID.getCode(), "消息超过大小限制",
                    single("maxMessageSize", properties.getMaxMessageSize()));
            ws.close(new CloseStatus(CloseStatus.TOO_BIG_TO_PROCESS.getCode(), "message too large"));
            return;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception e) {
            push.sendError(ws, ResultCode.BODY_PARSE_ERROR.getCode(),
                    ResultCode.BODY_PARSE_ERROR.getMessage(), null);
            return;
        }
        String type = text(root, "type");
        if (!StringUtils.hasText(type)) {
            push.sendError(ws, ResultCode.PARAM_INVALID.getCode(), "缺少 type", null);
            return;
        }

        JsonNode data = root.path("data");
        String clientMsgId = text(root, "clientMsgId");
        if (!StringUtils.hasText(clientMsgId)) {
            clientMsgId = text(data, "clientMsgId");
        }

        try {
            dispatch(ws, type, data, clientMsgId, root);
        } catch (BizException e) {
            push.sendError(ws, e.getCode(), e.getMessage(), context(type, clientMsgId));
        } catch (Exception e) {
            // 单条消息失败不关闭连接：客户端至少能看到错误并决定是否重试
            log.error("WS 消息处理异常 type={} userId={} connectionId={}",
                    type, ws.getUserId(), ws.getConnectionId(), e);
            push.sendError(ws, ResultCode.UNKNOWN.getCode(), ResultCode.UNKNOWN.getMessage(),
                    context(type, clientMsgId));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) {
        WsSession ws = session(socket);
        if (ws != null) {
            push.sendError(ws, ResultCode.FILE_TYPE_UNSUPPORTED.getCode(),
                    "仅支持 JSON 文本帧", null);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        WsSession ws = session(socket);
        if (ws == null) {
            return;
        }
        // 先摘本地索引，再清 Redis：本地表里挂着一个死连接比重复清理危险得多
        Long roomId = registry.unregister(ws);
        try {
            presence.unregisterSession(ws, roomId);
        } catch (Exception e) {
            log.warn("WS 关闭清理失败 connectionId={} cause={}", ws.getConnectionId(), e.toString());
        }
        // 同一用户还有别的设备在房间里：成员没有真正离开，不能广播 leave
        if (roomId != null && !registry.hasUserInRoom(ws.getUserId(), roomId)) {
            Map<String, Object> data = memberData(roomId, ws);
            data.put("onlineCount", presence.onlineCount(roomId));
            push.sendToRoomExcept(roomId, "room.member.leave", data, ws.getConnectionId());
            leaveManagedRoom(roomId, ws.getUserId());
        }
        log.info("WS 连接关闭 connectionId={} userId={} code={} current={}",
                ws.getConnectionId(), ws.getUserId(), status.getCode(), registry.totalConnections());
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable exception) {
        WsSession ws = session(socket);
        log.debug("WS 传输错误 connectionId={} cause={}",
                ws == null ? socket.getId() : ws.getConnectionId(), exception.toString());
        closeQuietly(socket, CloseStatus.SERVER_ERROR.withReason("transport error"));
    }

    // ------------------------------------------------------------------ 路由

    private void dispatch(WsSession ws, String type, JsonNode data, String clientMsgId,
                          JsonNode root) throws Exception {
        switch (type) {
            case "ping":
                onPing(ws, root);
                break;
            case "room.join":
                onJoin(ws, data, clientMsgId);
                break;
            case "room.leave":
                onLeave(ws);
                break;
            case "room.chat":
                onChat(ws, data, clientMsgId);
                break;
            case "room.danmaku":
                onDanmaku(ws, data);
                break;
            case "room.emoji":
                onEmoji(ws, data);
                break;
            case "room.vote.start":
                onVoteStart(ws, clientMsgId);
                break;
            case "room.vote.agree":
                onVoteAgree(ws, data, clientMsgId);
                break;
            case "room.like":
                onLike(ws);
                break;
            case "room.play":
            case "room.pause":
            case "room.seek":
            case "room.switch":
                onPlayControl(ws, type, data, clientMsgId);
                break;
            case "room.sync.request":
                onSyncRequest(ws);
                break;
            case "ROOM_SYNC_REQ":
                onClockSync(ws, data);
                break;
            default:
                push.sendError(ws, ResultCode.PARAM_INVALID.getCode(), "不支持的消息类型：" + type,
                        context(type, clientMsgId));
        }
    }

    /** 心跳只更新活跃时间与 Redis 在线状态，不写业务日志（BR-13-008）。 */
    private void onPing(WsSession ws, JsonNode root) {
        Map<String, Object> pong = new LinkedHashMap<>();
        pong.put("ts", root.path("ts").asLong(0L));
        pong.put("serverTime", System.currentTimeMillis());
        push.sendFrame(ws, "pong", pong);
        presence.refreshPresence(ws);
        maybeAuthExpiring(ws);
    }

    // ------------------------------------------------------------------ 房间

    /**
     * 加入房间。
     *
     * <p>两类房间：{@code t_room} 中存在的真实房间按 DB 规则校验（状态/黑名单/踢出冷却/
     * 满员/密码）；不存在的 roomId 继续走模块 04 的演示逻辑（首个进房者 SETNX 成为房主），
     * 这样模块 04 的联调脚本与前端 Demo 页面不受影响。
     */
    private void onJoin(WsSession ws, JsonNode data, String clientMsgId) {
        Long roomId = longValue(data, "roomId");
        if (roomId == null) {
            push.sendError(ws, ResultCode.PARAM_INVALID.getCode(), "缺少 roomId",
                    context("room.join", clientMsgId));
            return;
        }
        Room managed = roomService.findManagedRoom(roomId);
        if (managed == null && !properties.isDemoRoomEnabled()) {
            // 没有 t_room 可校验时 fail-closed，避免任意数字 roomId 都能进
            push.sendError(ws, ResultCode.ROOM_NOT_FOUND.getCode(),
                    ResultCode.ROOM_NOT_FOUND.getMessage(), context("room.join", clientMsgId));
            return;
        }

        // BR-14-018：重复 join 幂等 —— 只补发快照，不重复广播 member.join
        if (ws.getRoomId() != null && ws.getRoomId().equals(roomId)) {
            push.sendAck(ws, "room.join.ack", clientMsgId, joinAck(ws, roomId, managed));
            return;
        }

        if (managed != null) {
            // 校验失败直接抛 BizException，由 handleTextMessage 统一回 error 帧
            roomService.prepareJoin(roomId, ws.getUserId(), text(data, "password"));
        }
        if (ws.getRoomId() != null) {
            doLeave(ws);
        }
        registry.joinRoom(ws, roomId);
        presence.joinRoom(ws, roomId);
        if (managed != null) {
            roomService.onWsJoin(managed, ws.getUserId());
        }

        push.sendAck(ws, "room.join.ack", clientMsgId, joinAck(ws, roomId, managed));

        Map<String, Object> member = memberData(roomId, ws);
        member.put("onlineCount", presence.onlineCount(roomId));
        push.sendToRoomExcept(roomId, "room.member.join", member, ws.getConnectionId());
    }

    private Map<String, Object> joinAck(WsSession ws, Long roomId, Room managed) {
        if (managed != null) {
            return roomService.wsSnapshot(managed, ws.getUserId(), ws.getNickname());
        }
        Long ownerId = presence.claimOwner(roomId, ws.getUserId());
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("roomId", String.valueOf(roomId));
        ack.put("userId", String.valueOf(ws.getUserId()));
        ack.put("nickname", ws.getNickname());
        ack.put("ownerId", ownerId == null ? null : String.valueOf(ownerId));
        ack.put("isOwner", ownerId != null && ownerId.equals(ws.getUserId()));
        ack.put("onlineCount", presence.onlineCount(roomId));
        ack.put("snapshot", presence.playSnapshot(roomId));
        ack.put("serverTime", System.currentTimeMillis());
        return ack;
    }

    private void onLeave(WsSession ws) {
        if (ws.getRoomId() == null) {
            return;
        }
        doLeave(ws);
    }

    /** 离开房间的完整流程；重复调用是安全的（session.roomId 为 null 时直接返回）。 */
    private void doLeave(WsSession ws) {
        Long roomId = ws.getRoomId();
        if (roomId == null) {
            return;
        }
        // 顺序：先摘 registry（hasUserInRoom 才是「其它连接」的准确答案），再清 Redis 在线集合
        registry.leaveRoom(ws);
        presence.leaveRoom(ws.getUserId(), roomId);
        if (registry.hasUserInRoom(ws.getUserId(), roomId)) {
            return;
        }
        Map<String, Object> data = memberData(roomId, ws);
        data.put("onlineCount", presence.onlineCount(roomId));
        push.sendToRoomExcept(roomId, "room.member.leave", data, ws.getConnectionId());
        leaveManagedRoom(roomId, ws.getUserId());
    }

    /**
     * 真实房间的离房副作用：结算停留时长、释放麦位、房主离线自动交接。
     * 演示房间在 {@code t_room} 中不存在，这里直接跳过。
     */
    private void leaveManagedRoom(Long roomId, Long userId) {
        try {
            if (roomService.isManagedRoom(roomId)) {
                roomService.onWsLeave(roomId, userId);
            }
        } catch (Exception e) {
            // 断线清理失败不能影响连接关闭，更不能让定时任务兜底之外产生二次异常
            log.warn("离房结算失败 roomId={} userId={} cause={}", roomId, userId, e.toString());
        }
    }

    // ------------------------------------------------------------------ 聊天 / 弹幕

    /**
     * 房间聊天/弹幕的统一拒绝原因：账号限制、青少年模式、敏感词。
     * 返回 null 表示放行；WS 帧里的原因保持英文短码，前端已有映射。
     */
    private String contentRejection(Long userId, String content) {
        try {
            restrictionService.requireCanPublish(userId);
            minorService.requireSocialAllowed(userId);
        } catch (BizException e) {
            int code = e.getCode();
            if (code == ResultCode.MINOR_MODE_FEATURE_FORBIDDEN.getCode()
                    || code == ResultCode.MINOR_MODE_TIME_FORBIDDEN.getCode()
                    || code == ResultCode.MINOR_MODE_USAGE_LIMIT.getCode()) {
                return "minor_forbidden";
            }
            return "restricted";
        }
        return sensitiveWords.isRisky(content) ? "sensitive" : null;
    }

    private void onChat(WsSession ws, JsonNode data, String clientMsgId) throws Exception {
        Long roomId = ws.getRoomId();
        if (roomId == null) {
            reject(ws, "room.chat.rejected", null, clientMsgId, "not_in_room", null);
            return;
        }
        String content = trim(text(data, "content"));
        String invalid = validateContent(content, CHAT_MAX_LENGTH);
        if (invalid != null) {
            reject(ws, "room.chat.rejected", roomId, clientMsgId, invalid, null);
            return;
        }
        if (presence.isBlacklisted(roomId, ws.getUserId())) {
            reject(ws, "room.chat.rejected", roomId, clientMsgId, "blacklisted", null);
            return;
        }
        long muteSeconds = presence.muteRemainingSeconds(roomId, ws.getUserId());
        if (muteSeconds > 0) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("remainingSeconds", muteSeconds);
            reject(ws, "room.chat.rejected", roomId, clientMsgId, "muted", extra);
            return;
        }
        RateLimitService.Decision decision = rateLimit.check("ws-chat", RateLimitDimension.USER,
                String.valueOf(ws.getUserId()), properties.getChatRatePerMinute(),
                RATE_WINDOW_SECONDS, "chat");
        if (!decision.isAllowed()) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("retryAfterSeconds", decision.getRetryAfterSeconds());
            reject(ws, "room.chat.rejected", roomId, clientMsgId, "rate_limited", extra);
            return;
        }
        String rejection = contentRejection(ws.getUserId(), content);
        if (rejection != null) {
            reject(ws, "room.chat.rejected", roomId, clientMsgId, rejection, null);
            return;
        }
        if (!StringUtils.hasText(clientMsgId)) {
            // BR-13-012：可靠消息必须可被 ack 关联，否则客户端无法兑现乐观渲染
            reject(ws, "room.chat.rejected", roomId, clientMsgId, "missing_client_msg_id", null);
            return;
        }

        WsDedupService.Result acquired = dedup.tryAcquire(ws.getUserId(), clientMsgId);
        if (!acquired.isAcquired()) {
            if (acquired.getPreviousAck() != null) {
                push.replayRaw(ws, acquired.getPreviousAck());
            } else {
                push.sendError(ws, ResultCode.IDEMPOTENT_CONFLICT.getCode(),
                        "消息正在处理中，请勿重复提交", context("room.chat", clientMsgId));
            }
            return;
        }

        try {
            String messageId = IdUtil.getSnowflakeNextIdStr();
            long now = System.currentTimeMillis();
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("messageId", messageId);
            ack.put("roomId", String.valueOf(roomId));
            ack.put("serverTime", now);
            Map<String, Object> ackFrame = WsMessages.ack("room.chat.ack", clientMsgId, ack);
            dedup.complete(ws.getUserId(), clientMsgId,
                    objectMapper.writeValueAsString(ackFrame));

            // 先给发送方 ack（兑现乐观渲染），再广播给其他人。
            // 不回推发送方自己：ack 已经带了完整 messageId，再收一遍广播会让前端去重逻辑多一次判断。
            push.deliver(ws, ackFrame);

            Map<String, Object> broadcast = chatData(roomId, messageId, ws, content, now);
            Map<String, Object> frame = WsMessages.serverInRoom(roomId, "room.chat", broadcast);
            push.sendRoomMessage(roomId, frame, ws.getConnectionId());
            persistAsync(Long.parseLong(messageId), roomId, ws.getUserId(),
                    RoomMessage.TYPE_CHAT, content, null);
            roomService.recordInteraction(roomId);
            // 模块 13：活跃榜 +@ 提醒（提醒失败不影响聊天主流程）
            rankService.recordMessage(roomId, ws.getUserId());
            notifyMentions(roomId, ws, content);
        } catch (Exception e) {
            dedup.release(ws.getUserId(), clientMsgId);
            throw e;
        }
    }

    /**
     * 弹幕是不可靠消息（BR-13-036）：不重试、不回 ack、不去重。
     * 过载时允许丢弃，所以这里也不做「慢连接断开」之外的补偿。
     */
    private void onDanmaku(WsSession ws, JsonNode data) {
        Long roomId = ws.getRoomId();
        if (roomId == null) {
            reject(ws, "room.danmaku.rejected", null, null, "not_in_room", null);
            return;
        }
        String content = trim(text(data, "content"));
        String invalid = validateContent(content, DANMAKU_MAX_LENGTH);
        if (invalid != null) {
            reject(ws, "room.danmaku.rejected", roomId, null, invalid, null);
            return;
        }
        if (presence.isBlacklisted(roomId, ws.getUserId())) {
            reject(ws, "room.danmaku.rejected", roomId, null, "blacklisted", null);
            return;
        }
        long muteSeconds = presence.muteRemainingSeconds(roomId, ws.getUserId());
        if (muteSeconds > 0) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("remainingSeconds", muteSeconds);
            reject(ws, "room.danmaku.rejected", roomId, null, "muted", extra);
            return;
        }
        RateLimitService.Decision decision = rateLimit.check("ws-danmaku", RateLimitDimension.USER,
                String.valueOf(ws.getUserId()), properties.getDanmakuRatePerMinute(),
                RATE_WINDOW_SECONDS, "danmaku");
        if (!decision.isAllowed()) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("retryAfterSeconds", decision.getRetryAfterSeconds());
            reject(ws, "room.danmaku.rejected", roomId, null, "rate_limited", extra);
            return;
        }
        String rejection = contentRejection(ws.getUserId(), content);
        if (rejection != null) {
            reject(ws, "room.danmaku.rejected", roomId, null, rejection, null);
            return;
        }

        String messageId = IdUtil.getSnowflakeNextIdStr();
        long now = System.currentTimeMillis();
        Map<String, Object> broadcast = chatData(roomId, messageId, ws, content, now);
        broadcast.put("messageType", RoomMessage.TYPE_DANMAKU);
        Map<String, Object> frame = WsMessages.serverInRoom(roomId, "room.danmaku", broadcast);
        // 弹幕包含发送方：它没有 ack 可以依赖乐观渲染
        push.sendRoomMessage(roomId, frame, null);
        // 模块 13 新协议帧；新旧客户端都靠 messageId 去重（同一帧不落两次库）
        push.sendRoomMessage(roomId,
                WsMessages.serverInRoom(roomId, "ROOM_DANMAKU", broadcast), null);
        persistAsync(Long.parseLong(messageId), roomId, ws.getUserId(),
                RoomMessage.TYPE_DANMAKU, content, null);
        roomService.recordInteraction(roomId);
        rankService.recordMessage(roomId, ws.getUserId());
    }

    /** 快捷表情（FR-22-006）：预置集合 + 30 次/分钟。 */
    private void onEmoji(WsSession ws, JsonNode data) {
        Long roomId = ws.getRoomId();
        if (roomId == null) {
            reject(ws, "room.emoji.rejected", null, null, "not_in_room", null);
            return;
        }
        String emoji = trim(text(data, "emoji"));
        if (!StringUtils.hasText(emoji)) {
            emoji = trim(text(data, "content"));
        }
        if (!PRESET_EMOJIS.contains(emoji)) {
            reject(ws, "room.emoji.rejected", roomId, null, "invalid_emoji", null);
            return;
        }
        if (presence.isBlacklisted(roomId, ws.getUserId())) {
            reject(ws, "room.emoji.rejected", roomId, null, "blacklisted", null);
            return;
        }
        if (presence.muteRemainingSeconds(roomId, ws.getUserId()) > 0) {
            reject(ws, "room.emoji.rejected", roomId, null, "muted", null);
            return;
        }
        RateLimitService.Decision decision = rateLimit.check("ws-emoji", RateLimitDimension.USER,
                String.valueOf(ws.getUserId()), 30, RATE_WINDOW_SECONDS, "emoji");
        if (!decision.isAllowed()) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("retryAfterSeconds", decision.getRetryAfterSeconds());
            reject(ws, "room.emoji.rejected", roomId, null, "rate_limited", extra);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", IdUtil.getSnowflakeNextIdStr());
        payload.put("roomId", String.valueOf(roomId));
        payload.put("userId", String.valueOf(ws.getUserId()));
        payload.put("nickname", ws.getNickname());
        payload.put("emoji", emoji);
        payload.put("createTime", System.currentTimeMillis());
        push.sendRoomMessage(roomId,
                WsMessages.serverInRoom(roomId, "ROOM_EMOJI", payload), null);
        rankService.recordMessage(roomId, ws.getUserId());
    }

    /** 投票发起（WS 版，与 HTTP 共用 RoomVoteService 的锁与判定）。 */
    private void onVoteStart(WsSession ws, String clientMsgId) {
        Long roomId = ws.getRoomId();
        if (roomId == null) {
            push.sendError(ws, ResultCode.ROOM_NOT_FOUND.getCode(), "请先加入房间",
                    context("room.vote.start", clientMsgId));
            return;
        }
        RoomVoteVO vo = voteService.start(roomId, ws.getUserId());
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("voteId", vo.getVoteId());
        ack.put("roomId", String.valueOf(roomId));
        ack.put("songId", vo.getSongId());
        ack.put("endTime", vo.getEndTime());
        ack.put("onlineCount", vo.getOnlineCount());
        ack.put("serverTime", System.currentTimeMillis());
        push.sendAck(ws, "room.vote.start.ack", clientMsgId, ack);
    }

    /** 同意跳过当前歌曲。 */
    private void onVoteAgree(WsSession ws, JsonNode data, String clientMsgId) {
        Long roomId = ws.getRoomId();
        if (roomId == null) {
            push.sendError(ws, ResultCode.ROOM_NOT_FOUND.getCode(), "请先加入房间",
                    context("room.vote.agree", clientMsgId));
            return;
        }
        Long voteId = longValue(data, "voteId");
        if (voteId == null) {
            push.sendError(ws, ResultCode.PARAM_INVALID.getCode(), "缺少 voteId",
                    context("room.vote.agree", clientMsgId));
            return;
        }
        RoomVoteVO vo = voteService.agree(roomId, ws.getUserId(), voteId);
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("voteId", vo.getVoteId());
        ack.put("agreeCount", vo.getAgreeCount());
        ack.put("status", vo.getStatus());
        ack.put("voted", vo.getVoted());
        ack.put("serverTime", System.currentTimeMillis());
        push.sendAck(ws, "room.vote.agree.ack", clientMsgId, ack);
    }

    /** 给当前歌曲点赞。 */
    private void onLike(WsSession ws) {
        Long roomId = ws.getRoomId();
        if (roomId == null) {
            push.sendError(ws, ResultCode.ROOM_NOT_FOUND.getCode(), "请先加入房间", null);
            return;
        }
        push.sendServer(ws, "room.like.ack", rankService.like(roomId, ws.getUserId()));
    }

    /**
     * 时钟同步（FR-22-001）：原样回显客户端发送时间，客户端用收发时间算 RTT 与偏移。
     */
    private void onClockSync(WsSession ws, JsonNode data) {
        Long clientTime = longValue(data, "clientTime");
        if (clientTime == null) {
            clientTime = longValue(data, "ts");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientTime", clientTime);
        response.put("serverTime", System.currentTimeMillis());
        response.put("roomId", ws.getRoomId() == null ? null : String.valueOf(ws.getRoomId()));
        push.sendFrame(ws, "ROOM_SYNC_ACK", response);
    }

    private void notifyMentions(Long roomId, WsSession ws, String content) {
        try {
            Matcher matcher = MENTION_PATTERN.matcher(content == null ? "" : content);
            Set<String> names = new HashSet<>();
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            if (names.isEmpty()) {
                return;
            }
            RateLimitService.Decision decision = rateLimit.check("ws-mention",
                    RateLimitDimension.USER, String.valueOf(ws.getUserId()), 5,
                    RATE_WINDOW_SECONDS, "mention");
            if (!decision.isAllowed()) {
                return;
            }
            List<RoomMemberVO> members = roomService.members(roomId);
            Set<Long> notified = new HashSet<>();
            for (RoomMemberVO member : members) {
                if (member.getNickname() == null || !names.contains(member.getNickname())
                        || member.getUserId() == null
                        || member.getUserId().equals(ws.getUserId())
                        || !notified.add(member.getUserId())) {
                    continue;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("roomId", String.valueOf(roomId));
                payload.put("fromUserId", String.valueOf(ws.getUserId()));
                payload.put("fromNickname", ws.getNickname());
                payload.put("content", content);
                payload.put("serverTime", System.currentTimeMillis());
                push.sendToUser(member.getUserId(), "room.mention", payload);
            }
        } catch (Exception e) {
            log.debug("@ 提醒失败 roomId={} userId={} cause={}", roomId, ws.getUserId(), e.toString());
        }
    }

    /** @return 拒绝原因；null 表示通过 */
    private String validateContent(String content, int maxLength) {
        if (!StringUtils.hasText(content)) {
            return "empty";
        }
        if (content.length() > maxLength) {
            return "too_long";
        }
        return null;
    }

    // ------------------------------------------------------------------ 控播

    private void onPlayControl(WsSession ws, String type, JsonNode data, String clientMsgId)
            throws Exception {
        Long roomId = ws.getRoomId();
        if (roomId == null) {
            push.sendError(ws, ResultCode.ROOM_NOT_FOUND.getCode(), "请先加入房间",
                    context(type, clientMsgId));
            return;
        }
        Room managed = roomService.findManagedRoom(roomId);
        if (managed != null) {
            // 真实房间：状态 + 黑名单 + 播放模式（房主控播 / 自由播放 / 投票）都在 DB 规则里
            roomService.requireWsControl(roomId, ws.getUserId());
        } else {
            if (presence.isBlacklisted(roomId, ws.getUserId())) {
                push.sendError(ws, ResultCode.ROOM_BLACKLISTED.getCode(),
                        ResultCode.ROOM_BLACKLISTED.getMessage(), context(type, clientMsgId));
                return;
            }
            if (!presence.isOwner(roomId, ws.getUserId())) {
                // EX-13-004-1：普通成员无法控播；这是预期内的拒绝，不记 ERROR
                push.sendError(ws, ResultCode.ROOM_NO_PERMISSION.getCode(),
                        ResultCode.ROOM_NO_PERMISSION.getMessage(), context(type, clientMsgId));
                return;
            }
        }

        WsDedupService.Result acquired = dedup.tryAcquire(ws.getUserId(), clientMsgId);
        if (!acquired.isAcquired()) {
            if (acquired.getPreviousAck() != null) {
                push.replayRaw(ws, acquired.getPreviousAck());
            } else {
                push.sendError(ws, ResultCode.IDEMPOTENT_CONFLICT.getCode(),
                        "操作正在处理中，请勿重复提交", context(type, clientMsgId));
            }
            return;
        }

        try {
            if (managed != null) {
                applyManagedPlayControl(ws, roomId, type, data, clientMsgId);
            } else {
                applyPlayControl(ws, roomId, type, data, clientMsgId);
            }
        } catch (Exception e) {
            dedup.release(ws.getUserId(), clientMsgId);
            throw e;
        }
    }

    /**
     * 真实房间的控播（模块 13）。
     *
     * <p>状态机、锁、版本号、DB 当前歌曲同步、投票作废全部收在 {@link RoomPlayService}；
     * 这里只负责把 WS 的旧字段（position 秒）翻译成毫秒并回 ack。
     * 权限校验已在 {@link #onPlayControl} 用 {@code requireWsControl} 完成。
     */
    private void applyManagedPlayControl(WsSession ws, Long roomId, String type, JsonNode data,
                                         String clientMsgId) throws Exception {
        Long positionMs = longValue(data, "positionMs");
        if (positionMs == null) {
            Long legacyPosition = longValue(data, "position");
            positionMs = legacyPosition == null ? null : legacyPosition * 1000L;
        }
        Long volume = longValue(data, "volume");
        Map<String, Object> snapshot = playService.applyWsControl(roomId, ws.getUserId(),
                type, type, longValue(data, "songId"), positionMs,
                volume == null ? null : volume.intValue());

        if (StringUtils.hasText(clientMsgId)) {
            Map<String, Object> ackData = new LinkedHashMap<>();
            ackData.put("roomId", String.valueOf(roomId));
            ackData.put("version", snapshot.get("version"));
            ackData.put("playStateVersion", snapshot.get("playStateVersion"));
            ackData.put("serverTime", snapshot.get("serverTime"));
            Map<String, Object> ackFrame = WsMessages.ack(type + ".ack", clientMsgId, ackData);
            dedup.complete(ws.getUserId(), clientMsgId,
                    objectMapper.writeValueAsString(ackFrame));
            push.deliver(ws, ackFrame);
        }
    }

    /** @return 应用后的歌曲 ID；校验失败提前返回时为 null */
    private Long applyPlayControl(WsSession ws, Long roomId, String type, JsonNode data,
                                  String clientMsgId) throws Exception {
        Map<String, String> state = presence.playState(roomId);
        Long songId = longValue(data, "songId");
        Song song = null;
        if (songId != null) {
            song = songService.requirePlayable(songId);
        } else if (!StringUtils.hasText(state.get("songId"))) {
            push.sendError(ws, ResultCode.PARAM_INVALID.getCode(),
                    "房间当前没有正在播放的歌曲，请先切歌", context(type, clientMsgId));
            dedup.release(ws.getUserId(), clientMsgId);
            return null;
        }

        Map<String, String> fields = new LinkedHashMap<>();
        if (song != null) {
            fields.put("songId", String.valueOf(song.getId()));
            fields.put("songName", song.getName());
            fields.put("artistName", song.getArtistName());
            fields.put("cover", nullSafe(song.getCover()));
            fields.put("duration", song.getDuration() == null ? "0" : String.valueOf(song.getDuration()));
        } else {
            fields.put("songId", state.get("songId"));
            fields.put("songName", state.get("songName"));
            fields.put("artistName", state.get("artistName"));
            fields.put("cover", state.get("cover"));
            fields.put("duration", state.getOrDefault("duration", "0"));
        }

        long position = resolvePosition(type, data, state);
        boolean isPlaying = resolvePlaying(type, data, state);
        fields.put("position", String.valueOf(position));
        fields.put("isPlaying", String.valueOf(isPlaying));
        fields.put("operatorId", String.valueOf(ws.getUserId()));

        long version = presence.savePlayState(roomId, fields);
        Map<String, Object> snapshot = presence.playSnapshot(roomId);
        snapshot.put("roomId", String.valueOf(roomId));
        snapshot.put("songId", fields.get("songId"));
        snapshot.put("songName", fields.get("songName"));
        snapshot.put("artistName", fields.get("artistName"));
        snapshot.put("cover", fields.get("cover"));
        snapshot.put("duration", parseLong(fields.get("duration")));
        snapshot.put("position", position);
        snapshot.put("isPlaying", isPlaying);
        snapshot.put("operatorId", String.valueOf(ws.getUserId()));
        snapshot.put("version", version);
        snapshot.put("serverTime", System.currentTimeMillis());

        if (StringUtils.hasText(clientMsgId)) {
            Map<String, Object> ackData = new LinkedHashMap<>();
            ackData.put("roomId", String.valueOf(roomId));
            ackData.put("version", version);
            ackData.put("serverTime", snapshot.get("serverTime"));
            Map<String, Object> ackFrame = WsMessages.ack(type + ".ack", clientMsgId, ackData);
            dedup.complete(ws.getUserId(), clientMsgId,
                    objectMapper.writeValueAsString(ackFrame));
            push.deliver(ws, ackFrame);
        }

        // 权威状态广播给全员（含发送方）：客户端只应用 version 更大的消息（BR-13-021）
        push.sendRoomMessage(roomId, WsMessages.serverInRoom(roomId, type, snapshot), null);
        return parseLong(fields.get("songId"));
    }

    private long resolvePosition(String type, JsonNode data, Map<String, String> state) {
        Long explicit = longValue(data, "position");
        long base = parseLong(state.get("position")) == null ? 0L : parseLong(state.get("position"));
        switch (type) {
            case "room.play":
                return explicit == null ? 0L : Math.max(0L, explicit);
            case "room.switch":
                return explicit == null ? 0L : Math.max(0L, explicit);
            case "room.seek":
                return explicit == null ? base : Math.max(0L, explicit);
            case "room.pause":
            default:
                if (explicit != null) {
                    return Math.max(0L, explicit);
                }
                // 没给位置就按「当前应到位置」暂停，避免暂停后进度跳回旧值
                if (Boolean.parseBoolean(state.getOrDefault("isPlaying", "false"))) {
                    long serverTime = parseLong(state.get("serverTime")) == null
                            ? System.currentTimeMillis() : parseLong(state.get("serverTime"));
                    return base + Math.max(0L, (System.currentTimeMillis() - serverTime) / 1000L);
                }
                return base;
        }
    }

    private boolean resolvePlaying(String type, JsonNode data, Map<String, String> state) {
        switch (type) {
            case "room.play":
            case "room.switch":
                return true;
            case "room.pause":
                return false;
            case "room.seek":
            default:
                JsonNode flag = data == null ? null : data.get("isPlaying");
                if (flag != null && !flag.isNull()) {
                    return flag.asBoolean();
                }
                return Boolean.parseBoolean(state.getOrDefault("isPlaying", "false"));
        }
    }

    private void onSyncRequest(WsSession ws) {
        Long roomId = ws.getRoomId();
        if (roomId == null) {
            push.sendError(ws, ResultCode.ROOM_NOT_FOUND.getCode(), "请先加入房间", null);
            return;
        }
        Map<String, Object> snapshot = presence.playSnapshot(roomId);
        snapshot.put("roomId", String.valueOf(roomId));
        push.deliver(ws, WsMessages.serverInRoom(roomId, "room.sync", snapshot));
    }

    /**
     * 兜底同步（BR-13-024）。只投本机：每个节点各自跑，跨节点再发布会导致 N² 条重复。
     */
    @Scheduled(fixedDelayString = "${newmusic.ws.room-sync-interval-ms:60000}")
    public void periodicRoomSync() {
        for (String roomKey : registry.roomCounts().keySet()) {
            Long roomId = parseLong(roomKey);
            if (roomId == null) {
                continue;
            }
            try {
                Map<String, String> state = presence.playState(roomId);
                if (!StringUtils.hasText(state.get("songId"))) {
                    continue;
                }
                Map<String, Object> snapshot = presence.playSnapshot(roomId);
                snapshot.put("roomId", String.valueOf(roomId));
                push.deliverToRoomLocal(roomId,
                        WsMessages.serverInRoom(roomId, "room.sync", snapshot));
            } catch (Exception e) {
                log.warn("周期 room.sync 失败 roomId={} cause={}", roomId, e.toString());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void persistAsync(long messageId, long roomId, long userId, int messageType,
                              String content, String extra) {
        try {
            messageExecutor.execute(() -> roomMessageService.append(
                    messageId, roomId, userId, messageType, content, extra, 1));
        } catch (Exception e) {
            log.warn("落库任务提交失败，实时广播不受影响 messageId={} cause={}",
                    messageId, e.toString());
        }
    }

    private Map<String, Object> chatData(Long roomId, String messageId, WsSession ws,
                                         String content, long now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", messageId);
        data.put("roomId", String.valueOf(roomId));
        data.put("userId", String.valueOf(ws.getUserId()));
        data.put("nickname", ws.getNickname());
        data.put("content", content);
        data.put("messageType", RoomMessage.TYPE_CHAT);
        data.put("createTime", now);
        return data;
    }

    private Map<String, Object> memberData(Long roomId, WsSession ws) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roomId", String.valueOf(roomId));
        data.put("userId", String.valueOf(ws.getUserId()));
        data.put("nickname", ws.getNickname());
        data.put("connectionId", ws.getConnectionId());
        data.put("serverTime", System.currentTimeMillis());
        return data;
    }

    private void reject(WsSession ws, String rejectedType, Long roomId, String clientMsgId,
                        String reason, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roomId", roomId == null ? null : String.valueOf(roomId));
        data.put("reason", reason);
        if (extra != null) {
            data.putAll(extra);
        }
        Map<String, Object> frame = WsMessages.ack(rejectedType, clientMsgId, data);
        if (roomId != null) {
            frame.put("roomId", String.valueOf(roomId));
        }
        push.deliver(ws, frame);
    }

    private void maybeAuthExpiring(WsSession ws) {
        long expireAt = ws.getTokenExpireAt();
        if (expireAt <= 0) {
            return;
        }
        long remaining = expireAt - System.currentTimeMillis();
        if (remaining <= 0) {
            return;
        }
        if (remaining <= properties.getAuthExpiringSeconds() * 1000L
                && ws.markAuthExpiringNotified()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("expireAt", expireAt);
            data.put("remainingSeconds", remaining / 1000L);
            push.sendFrame(ws, "auth.expiring", data);
        }
    }

    private static Map<String, Object> context(String type, String clientMsgId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("type", type);
        if (clientMsgId != null) {
            context.put("clientMsgId", clientMsgId);
        }
        return context;
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private WsSession session(WebSocketSession socket) {
        Object value = socket.getAttributes().get(ATTR_SESSION);
        return value instanceof WsSession ? (WsSession) value : null;
    }

    private static void closeQuietly(WebSocketSession socket, CloseStatus status) {
        try {
            if (socket.isOpen()) {
                socket.close(status);
            }
        } catch (Exception ignored) {
            // 已断开的连接再关一次没有意义
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long longValue(JsonNode node, String field) {
        return parseLong(text(node, field));
    }

    private static Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return value == null ? null : parseLong(String.valueOf(value));
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
