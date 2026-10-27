# 13 · 模块 04：WebSocket 实时通信

[← 上一章：模块03 Redis缓存](./12-模块03-Redis缓存.md) | [返回目录](./README.md) | [下一章：模块05 音乐房 →](./14-模块05-音乐房.md)

> **优先级**：P0
> **对应提示词条目**：4. WebSocket

---

## 1. 模块目标

为音乐房、私信、通知提供**低延迟、可靠、可水平扩展**的实时双向通信能力。

**衡量指标**：

| 指标 | 目标 |
|---|---|
| 单条消息端到端延迟（同机房） | P95 ≤ 200ms |
| 单实例 WebSocket 连接数 | ≥ 10000 |
| 消息丢失率 | ≤ 0.01%（可靠消息） |
| 断线重连成功率 | ≥ 99% |
| 心跳开销 | ≤ 1 次/30s 每连接 |

---

## 2. 使用角色

| 角色 | 使用场景 |
|---|---|
| 全部用户 | 接收通知、私信提醒、@提醒 |
| 房间成员 | 房间聊天、弹幕、控播同步、麦位变更 |
| 房主/房管 | 控播、踢人、禁言、公告广播 |

---

## 3. 前置条件

- Spring Boot WebSocket 已配置。
- Redis 可用（会话与房间状态）。
- RocketMQ 可用（集群广播）。
- Nginx 已配置 WebSocket 升级（`Upgrade` / `Connection` 头）。

---

## 4. 技术实现方案

### 4.1 技术选型

| 项 | 选型 | 理由 |
|---|---|---|
| 协议 | **原生 WebSocket**（RFC 6455） | 协议可控，无 STOMP 复杂度 |
| 消息格式 | **JSON**（自定义协议） | 易调试、易扩展 |
| 服务端 | Spring `WebSocketHandler` + Netty（可选） | 与 Spring 上下文共享 |
| 心跳 | **应用层 ping/pong**（30s） | 穿透所有代理，可控 |
| 重连 | 客户端指数退避 | 见 5.6 节 |
| 集群广播 | **RocketMQ 广播模式** | 见 5.7 节 |
| 鉴权 | 连接时 `?token=xxx` 或首帧 `auth` 消息 | 见 4.3 节 |
| 压缩 | `permessage-deflate`（可选） | 省带宽，CPU 略升 |

> **【假设 A-17】** 不考虑 STOMP/SockJS 兼容层。目标浏览器均原生支持 WebSocket（Chrome 16+、Safari 7+），无需降级到长轮询。若后续需兼容极老环境，再引入 SockJS。

### 4.2 服务端配置

```java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired private RoomWsHandler roomWsHandler;
    @Autowired private WsHandshakeInterceptor handshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(roomWsHandler, "/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(allowedOrigins());   // 生产环境配置白名单，禁止 *
    }
}

@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // 1. 从 query 参数或 Header 取 token
        String token = extractToken(request);
        if (StrUtil.isBlank(token)) throw new BizException(UNAUTHORIZED);
        // 2. 校验 JWT
        LoginUser user = jwtUtil.parse(token);
        if (user == null) return false;
        // 3. 校验封禁状态
        if (redis.hasKey("ml:auth:userban:" + user.getUserId())) return false;
        // 4. 写入 attributes，供 Handler 使用
        attributes.put("userId", user.getUserId());
        attributes.put("nickname", user.getNickname());
        attributes.put("deviceId", extractDeviceId(request));
        return true;
    }
}
```

### 4.3 鉴权方案

**方案对比**：

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| `ws://host/ws?token=xxx` | 简单直观 | token 出现在 URL（日志泄露风险） | **采用，但日志脱敏** |
| 首帧 auth 消息 | token 不进 URL | 需处理未鉴权连接 | 备选 |
| Cookie | 自动携带 | 无法跨域、CSRF 风险 | 不采用 |

**规则**：

- 连接 URL：`wss://api.musiclive.com/ws?token={accessToken}&deviceId={xxx}`
- 握手阶段校验 token，失败直接拒绝握手（HTTP 401）。
- 服务端日志**必须**对 `token` 参数脱敏。
- token 剩余有效期 < 5 分钟时，服务端发送 `auth.expiring` 通知，客户端主动刷新并重连。

---

## 5. 功能需求明细

### FR-13-001 连接建立与鉴权 【P0】

| 项 | 内容 |
|---|---|
| **功能目标** | 建立已鉴权的 WebSocket 连接并注册会话 |
| **使用角色** | 全部登录用户 |
| **前置条件** | 用户已登录且持有有效 accessToken |

**操作流程**：

1. 客户端 `new WebSocket("wss://host/ws?token=xxx&deviceId=yyy")`。
2. 服务端握手拦截器校验 token → 通过则建立连接。
3. 连接建立后，服务端注册会话：`WsSessionRegistry.register(userId, session)`。
4. 服务端写 Redis：`ml:ws:session:{userId}` = `{nodeId}:{sessionId}`，`ml:ws:node:{nodeId}` 连接数 +1。
5. 服务端下发首帧 `connected` 消息（含 `connectionId`、`serverTime`、`heartbeatInterval`）。
6. 客户端收到后标记为已连接，开始心跳。

**首帧消息**：

```json
{
  "type": "connected",
  "data": {
    "connectionId": "n1-s12345",
    "serverTime": 1760246400000,
    "heartbeatInterval": 30000,
    "maxMessageSize": 65536
  }
}
```

**业务规则**：

- `BR-13-001`：同一用户**允许多设备同时连接**，但同一 `deviceId` 只保留最新连接（旧连接被关闭）。
- `BR-13-002`：握手超时 5s，超时拒绝。
- `BR-13-003`：单个 IP 连接数上限 20（防刷）。
- `BR-13-004`：用户被封禁时，握手直接拒绝，且已有连接被服务端主动关闭。
- `BR-13-005`：服务端在 `afterConnectionEstablished` 中完成会话注册，注册失败则关闭连接。

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-13-001-1` | token 无效/过期 | 握手返回 401，客户端跳登录页 |
| `EX-13-001-2` | token 缺失 | 握手返回 401 |
| `EX-13-001-3` | 用户被封禁 | 握手返回 403 |
| `EX-13-001-4` | 单 IP 超限 | 握手返回 429 |
| `EX-13-001-5` | 服务端连接数达上限 | 握手返回 503，客户端退避重连 |
| `EX-13-001-6` | 重复 deviceId 连接 | 关闭旧连接，保留新连接 |

**验收标准**：

- `AC-13-001-1`：无 token 连接被拒绝，返回 401
- `AC-13-001-2`：封禁用户无法建立连接
- `AC-13-001-3`：同一 deviceId 二次连接，旧连接被关闭
- `AC-13-001-4`：连接建立后 1 秒内收到 `connected` 帧
- `AC-13-001-5`：单实例支持 10000 连接不断开

---

### FR-13-002 心跳检测 【P0】

| 项 | 内容 |
|---|---|
| **功能目标** | 探测连接存活，及时清理死连接 |
| **使用角色** | 系统内部 |

**机制**：

```
客户端每 30s 发送:  {"type":"ping","ts":1760246400000}
服务端立即回复:      {"type":"pong","ts":1760246400000,"serverTime":...}
服务端每 30s 检查:   若 session.lastActiveAt 距今 > 90s → 关闭连接
```

**业务规则**：

- `BR-13-006`：客户端心跳间隔 **30s**（服务端在 `connected` 帧中下发，便于调整）。
- `BR-13-007`：服务端超时判定 **90s**（3 个心跳周期），超时关闭连接并清理会话。
- `BR-13-008`：心跳消息**不写业务日志**（避免日志爆炸），仅更新 `lastActiveAt`。
- `BR-13-009`：服务端定时任务每 30s 扫描一次超时会话（或使用 IdleStateHandler 更精确）。
- `BR-13-010`：客户端连续 2 次未收到 pong 视为断线，主动重连。
- `BR-13-011`：心跳计入 Redis 房间在线状态维护（见 [12 章 FR-12-007](./12-模块03-Redis缓存.md)），心跳时刷新 `ml:room:user:{userId}` TTL。

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-13-002-1` | 客户端无心跳 | 90s 后服务端关闭连接 |
| `EX-13-002-2` | 网络半开连接 | 心跳超时检测并清理 |
| `EX-13-002-3` | pong 丢失 | 客户端重发 1 次，仍无响应则重连 |

**验收标准**：

- `AC-13-002-1`：正常连接每 30s 一次 ping/pong
- `AC-13-002-2`：客户端停止心跳 90s 后连接被服务端关闭
- `AC-13-002-3`：心跳消息不产生 INFO 级日志

---

### FR-13-003 房间实时聊天与弹幕 【P0】

| 项 | 内容 |
|---|---|
| **功能目标** | 房间内成员实时交流 |
| **使用角色** | 房间成员（游客只读） |
| **前置条件** | 已连接 WebSocket 且已加入房间频道 |

**消息流**：

```
客户端 → {"type":"room.chat","data":{"roomId":"...","content":"这首歌真好听","clientMsgId":"uuid"}}
服务端处理：
  1. 校验：在房 / 未禁言 / 未黑名单 / 限流
  2. 敏感词过滤（同步，DFA）
  3. 若命中严重词 → 拦截，返回 room.chat.rejected
  4. 若需 AI 审核 → 先放行（低风险）或先拦截（高风险）
  5. 生成 messageId，落库 t_room_message（异步）
  6. 广播：投递 RocketMQ ml_room_broadcast_v1（topic: roomId）
  7. 本机订阅者收到 → 推送
客户端 ← {"type":"room.chat","data":{"messageId":"...","userId":"...","nickname":"...","content":"...","createTime":...}}
```

**弹幕与聊天区别**：

| 维度 | 聊天 | 弹幕 |
|---|---|---|
| 展示位置 | 右侧消息列表 | 播放器浮层横滑 |
| 类型值 | `room.chat` | `room.danmaku` |
| 频率限制 | 20 条/分钟 | 10 条/分钟 |
| 是否落库 | 落库 | 落库（可选择性丢弃） |
| 长度限制 | 200 字符 | 50 字符 |

**业务规则**：

- `BR-13-012`：消息**必带 `clientMsgId`**（客户端生成 UUID），服务端回执携带，用于客户端确认与去重。
- `BR-13-013`：服务端返回 `room.chat.ack` 携带 `clientMsgId` + `messageId` + `serverTime`。
- `BR-13-014`：消息内容**必须**经过敏感词过滤（见 [27 章](./27-模块18-内容安全.md)）。
- `BR-13-015`：禁言用户发送消息返回 `room.chat.rejected`，reason=`muted`，带剩余时长。
- `BR-13-016`：消息按 `messageId`（雪花，单调递增）排序，客户端按此排序展示（不依赖本地时间）。
- `BR-13-017`：客户端本地最多保留 200 条消息，超出丢弃最早的。
- `BR-13-018`：发送方采用**乐观渲染**：立即上屏（半透明），收到 ack 后转为正常态；收到 rejected 则移除并提示。

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-13-003-1` | 未加入房间就发言 | 返回 `room.chat.rejected`，reason=`not_in_room` |
| `EX-13-003-2` | 被禁言 | 返回 rejected，显示剩余时间 |
| `EX-13-003-3` | 命中敏感词（拦截级） | 返回 rejected，reason=`sensitive`，不入库不广播 |
| `EX-13-003-4` | 触发限流 | 返回 rejected，reason=`rate_limited` |
| `EX-13-003-5` | 消息过长 | rejected，reason=`too_long` |
| `EX-13-003-6` | 落库失败 | 不影响广播（优先保证实时性），异步补偿 |
| `EX-13-003-7` | 广播 MQ 失败 | 降级为**仅本机推送**（同节点用户可见）+ 告警 |

**验收标准**：

- `AC-13-003-1`：房间内 A 发消息，B 在 200ms 内收到
- `AC-13-003-2`：含敏感词的聊天被拦截，其他成员看不到
- `AC-13-003-3`：禁言用户发送失败并提示剩余时间
- `AC-13-003-4`：发送方乐观渲染，ack 后状态正确
- `AC-13-003-5`：消息顺序与 `messageId` 一致（无乱序）

---

### FR-13-004 同步播放控制 【P0】

| 项 | 内容 |
|---|---|
| **功能目标** | 房间内所有成员播放进度一致 |
| **使用角色** | 房主/房管（发指令）、全体成员（接收） |
| **前置条件** | 用户在房间内，播放态已从服务端同步 |

**指令类型**：

| 指令 | type | 说明 |
|---|---|---|
| 播放 | `room.play` | 从指定位置开始播放 |
| 暂停 | `room.pause` | 在指定位置暂停 |
| 切歌 | `room.switch` | 切换歌曲 |
| 跳转 | `room.seek` | 跳转到指定进度 |
| 状态同步 | `room.sync` | 服务端主动下发权威状态（新用户加入/周期性） |

**消息结构（房主 → 服务端）**：

```json
{
  "type": "room.play",
  "data": {
    "roomId": "1857...",
    "songId": "1857...",
    "position": 0,
    "clientVersion": 127
  }
}
```

**服务端广播（服务端 → 全员）**：

```json
{
  "type": "room.play",
  "data": {
    "songId": "1857...",
    "songName": "晴天",
    "artistName": "周杰伦",
    "cover": "https://...",
    "duration": 269,
    "position": 0,
    "serverTime": 1760246400123,
    "isPlaying": true,
    "operatorId": "1857...",
    "version": 128
  }
}
```

**客户端进度计算（核心算法）**：

```typescript
// 收到 room.play / room.sync 后
const { position, serverTime, isPlaying } = msg.data;
// 计算网络时延补偿（可选，精度要求高时启用）
const latency = (Date.now() - localSendTime) / 2;

function computePosition(): number {
  if (!isPlaying) return position;
  const elapsedSinceServer = (Date.now() - serverTime) / 1000;
  return position + elapsedSinceServer + latencyCompensation;
}

// 每秒校验一次，偏差超过阈值则校准
const drift = Math.abs(audio.currentTime - computePosition());
if (drift > 1.5) {
  audio.currentTime = computePosition();   // 硬校准（可能产生瞬间跳变）
} else if (drift > 0.5) {
  // 软校准：微调 playbackRate（人耳不可感知）
  audio.playbackRate = drift > 0 ? 1.02 : 0.98;
  setTimeout(() => { audio.playbackRate = 1.0; }, 2000);
}
```

**业务规则**：

- `BR-13-019`：**服务端为播放态唯一权威**，客户端不接受其他客户端的直接指令（必须经服务端）。
- `BR-13-020`：播放态存 Redis `ml:room:play:{roomId}`（见 [12 章](./12-模块03-Redis缓存.md)），`position` + `serverTime` 成对存储。
- `BR-13-021`：每次控播 `version` 递增；客户端**只应用 version 更大的消息**（防乱序/重放）。
- `BR-13-022`：偏差 ≤ 0.5s 不校准；0.5s ~ 1.5s 软校准（调 playbackRate）；> 1.5s 硬校准（seek）。
- `BR-13-023`：新成员加入房间时，服务端主动下发 `room.sync`（含最新权威状态）。
- `BR-13-024`：服务端每 60s 下发一次 `room.sync` 作为兜底（防累积漂移）。
- `BR-13-025`：网络抖动导致音频卡顿时不立即 seek，等待 2 秒稳定后再判断。
- `BR-13-026`：房间播放模式为"自由播放"（`play_mode=2`）时，不强制同步，仅展示歌曲信息。

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-13-004-1` | 非房主发送控播指令 | 忽略 + 返回 `room.error`（无权限） |
| `EX-13-004-2` | 客户端收到旧 version | 忽略该消息 |
| `EX-13-004-3` | 长时间无 sync（> 2min） | 客户端主动请求 `room.sync.request` |
| `EX-13-004-4` | 歌曲在房间中途被下架 | 服务端主动切下一首并广播 |
| `EX-13-004-5` | 客户端音频加载慢 | 允许最多 5s 追赶延迟，超过则直接 seek |

**验收标准**：

- `AC-13-004-1`：50 人房间播放进度偏差 ≤ 1.5s（持续 10 分钟）
- `AC-13-004-2`：新成员加入后 500ms 内播放进度对齐
- `AC-13-004-3`：乱序到达的旧 version 消息被忽略
- `AC-13-004-4`：普通成员无法控制播放
- `AC-13-004-5`：房主切歌后全员 500ms 内切换

---

### FR-13-005 系统通知与提醒 【P0】

| 项 | 内容 |
|---|---|
| **功能目标** | 实时推送各类通知，提升互动感知 |
| **使用角色** | 全部登录用户 |

**通知类型与消息类型映射**：

| 通知 | type | 触发 | 载荷 |
|---|---|---|---|
| 评论回复 | `notify.comment` | 有人回复我的评论 | `{commentId, fromUser, content, targetType, targetId}` |
| 点赞 | `notify.like` | 有人赞我的内容 | `{targetType, targetId, fromUser}` |
| 关注 | `notify.follow` | 有人关注我 | `{fromUser}` |
| 私信 | `notify.message` | 收到私信 | `{conversationId, fromUser, preview}` |
| @提醒 | `notify.mention` | 被 @ | `{fromUser, context, targetType, targetId}` |
| 房间邀请 | `notify.room_invite` | 被邀请进房 | `{roomId, roomName, fromUser}` |
| 审核结果 | `notify.audit` | 内容审核完成 | `{bizType, bizId, result, reason}` |
| 举报结果 | `notify.report` | 举报处理完成 | `{reportId, result}` |
| 系统公告 | `notify.system` | 运营推送 | `{title, content, link}` |

**统一消息结构**：

```json
{
  "type": "notify.comment",
  "msgId": "1857...",
  "data": {
    "notificationId": "1857...",
    "notifyType": 2,
    "title": "小明回复了你的评论",
    "content": "我也很喜欢这首歌！",
    "fromUser": { "id": "...", "nickname": "小明", "avatar": "..." },
    "targetType": 1,
    "targetId": "1857...",
    "createTime": 1760246400000
  }
}
```

**业务规则**：

- `BR-13-027`：通知**先落库**（`t_notification`）再推送，保证离线用户上线后可拉取。
- `BR-13-028`：推送为**尽力而为**：用户不在线则跳过推送（不重试），离线消息通过 HTTP 拉取。
- `BR-13-029`：同一内容的点赞/评论通知**聚合**：5 分钟内的多次点赞合并为一条（"小明等 5 人赞了你"）。
- `BR-13-030`：用户可配置通知开关（按类型），关闭的类型不推送也不落库（系统公告除外）。
- `BR-13-031`：未读数实时维护在 Redis `ml:notify:unread:{userId}`，推送时 +1。
- `BR-13-032`：通知保留 3 个月，超过自动清理。

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-13-005-1` | 用户不在线 | 仅落库，不推送 |
| `EX-13-005-2` | 推送失败 | 忽略（不重试），用户可通过拉取获得 |
| `EX-13-005-3` | 通知落库失败 | 记 ERROR；不推送（保证数据一致性） |
| `EX-13-005-4` | 被拉黑用户的通知 | 不产生通知 |

**验收标准**：

- `AC-13-005-1`：收到评论回复后 500ms 内收到 WS 推送
- `AC-13-005-2`：离线用户上线后可通过 HTTP 拉取到通知
- `AC-13-005-3`：5 分钟内多条点赞聚合为一条
- `AC-13-005-4`：关闭通知类型后不再收到该类推送

---

### FR-13-006 断线重连与消息补偿 【P0】

| 项 | 内容 |
|---|---|
| **功能目标** | 网络抖动后自动恢复，不丢消息 |
| **使用角色** | 全部用户 |

**重连策略（指数退避 + 抖动）**：

```typescript
const RECONNECT_DELAYS = [1000, 2000, 4000, 8000, 16000, 30000]; // 上限 30s

class WsClient {
  private retryCount = 0;

  onClose() {
    const base = RECONNECT_DELAYS[Math.min(this.retryCount, RECONNECT_DELAYS.length - 1)];
    const jitter = Math.random() * 1000;                  // 防惊群
    const delay = base + jitter;
    this.retryCount++;
    setTimeout(() => this.connect(), delay);
  }

  onOpen() {
    this.retryCount = 0;                                   // 成功则重置
    this.restoreState();                                   // 恢复订阅与状态
  }
}
```

**重连后状态恢复流程**：

```
1. 重新建立 WS 连接（携带最新 token）
2. 重新订阅：房间频道（若在房间）、用户频道
3. 请求 `room.sync.request` 拉取房间最新权威播放态
4. 请求 `room.messages.since` 拉取断线期间的消息（按 lastMessageId）
5. 请求未读通知数 `ml:notify:unread:{userId}`
6. 恢复 UI 状态
```

**消息可靠性设计（客户端确认 + 服务端重推）**：

```
客户端发送可靠消息（如聊天）:
  → 发送 { type, clientMsgId }
  → 启动超时定时器（5s）
  → 收到 ack（含 clientMsgId）→ 清除定时器，标记成功
  → 超时未收到 → 重发（最多 2 次，相同 clientMsgId，服务端幂等去重）
  → 仍失败 → 提示"发送失败，点击重试"

服务端接收:
  → 用 clientMsgId 查 Redis ml:ws:dedup:{userId}:{clientMsgId}
  → 已存在 → 直接返回上次的 ack（幂等）
  → 不存在 → 处理 + 写 dedup（TTL 5min）+ 返回 ack
```

**业务规则**：

- `BR-13-033`：重连间隔指数退避，上限 30s，**必须**加随机抖动（防全部客户端同时重连打爆服务端）。
- `BR-13-034`：重连成功后**必须**重新同步房间状态与补偿消息。
- `BR-13-035`：可靠消息（聊天、控播、私信）使用 `clientMsgId` 实现**幂等**。
- `BR-13-036`：不可靠消息（弹幕、心跳、输入中提示）**不做**重试。
- `BR-13-037`：断线期间的消息补偿通过 HTTP 接口拉取（`GET /room/{id}/messages?sinceId=xxx`），不依赖 WS。
- `BR-13-038`：页面从后台切回前台（`visibilitychange`）时，若连接已断则立即重连（不等退避）。
- `BR-13-039`：连续重连失败 10 次后，停止自动重连，展示"连接失败，点击重试"。

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-13-006-1` | 网络瞬断（< 5s） | 自动重连并恢复，用户无感 |
| `EX-13-006-2` | 长时间断网 | 指数退避重连；恢复后补偿消息 |
| `EX-13-006-3` | token 在断线期间过期 | 重连前先刷新 token |
| `EX-13-006-4` | 重连后房间已解散 | 提示并跳回房间列表 |
| `EX-13-006-5` | 消息重发但服务端已处理 | 幂等去重，返回原 ack |
| `EX-13-006-6` | 服务端重启 | 客户端重连，重新订阅 |

**验收标准**：

- `AC-13-006-1`：断网 5 秒后恢复，自动重连且消息不丢
- `AC-13-006-2`：重连间隔符合指数退避且含抖动
- `AC-13-006-3`：重发同一 `clientMsgId` 的消息服务端只处理一次
- `AC-13-006-4`：断线 1 分钟期间的消息，重连后可完整拉取
- `AC-13-006-5`：页面切回前台后 1 秒内重连（若已断）

---

### FR-13-007 WebSocket 集群广播 【P0】

| 项 | 内容 |
|---|---|
| **功能目标** | 多实例部署下，房间消息能触达所有订阅者 |
| **使用角色** | 系统内部 |

**问题**：用户 A 连接在实例 1，用户 B 连接在实例 2。A 发消息时，实例 1 只能推送给本机用户，无法触达 B。

**方案：RocketMQ 广播模式（Broadcasting）**

```
用户 A (实例1) 发消息
   ↓
实例1 处理（校验/过滤/落库）
   ↓
投递到 RocketMQ Topic: ml_room_broadcast_v1（带 roomId 作为 sharding key）
   ↓
所有 WS 实例都订阅该 Topic 的广播消费组（每个实例都收到全量消息）
   ↓
实例1: 查本地会话表 → 有订阅该 roomId 的用户？→ 推送
实例2: 查本地会话表 → 有订阅该 roomId 的用户？→ 推送
实例N: 无本地订阅者 → 直接丢弃（零成本）
```

**为什么用广播模式而非集群模式**：

| 模式 | 行为 | 适用 |
|---|---|---|
| 集群消费（Clustering） | 一条消息只被一个实例消费 | 任务分发、埋点 |
| **广播消费（Broadcasting）** | **每个实例都消费全量消息** | **房间广播（本场景）** |

**关键实现**：

```java
@Service
public class RoomBroadcastProducer {
    @Autowired private RocketMQTemplate rocketMQTemplate;

    public void broadcast(Long roomId, WsMessage message) {
        // roomId 作为 shardingKey，保证同房间消息有序
        rocketMQTemplate.syncSendOrderly(
            "ml_room_broadcast_v1",
            JSON.toJSONString(message),
            String.valueOf(roomId)      // 顺序键
        );
    }
}

@Component
@RocketMQMessageListener(
    topic = "ml_room_broadcast_v1",
    consumerGroup = "ml-ws-broadcast-group",
    messageModel = MessageModel.BROADCASTING   // ← 关键：广播模式
)
public class RoomBroadcastConsumer implements RocketMQListener<String> {

    @Autowired private WsSessionRegistry sessionRegistry;

    @Override
    public void onMessage(String body) {
        WsMessage msg = JSON.parseObject(body, WsMessage.class);
        Long roomId = msg.getRoomId();
        // 查本地会话表：本机是否有订阅该房间的用户
        Set<WsSession> sessions = sessionRegistry.getRoomSessions(roomId);
        if (CollUtil.isEmpty(sessions)) return;    // 无订阅者，直接丢弃
        for (WsSession s : sessions) {
            s.sendMessage(msg);                    // 推送（内部处理背压）
        }
    }
}
```

**本地会话表设计**：

```java
@Component
public class WsSessionRegistry {
    // userId → session
    private final Map<Long, WsSession> userSessions = new ConcurrentHashMap<>();
    // roomId → Set<userId>
    private final Map<Long, Set<Long>> roomSubscribers = new ConcurrentHashMap<>();

    public void subscribeRoom(Long roomId, Long userId) {
        roomSubscribers.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(userId);
    }
    public Set<WsSession> getRoomSessions(Long roomId) {
        Set<Long> uids = roomSubscribers.get(roomId);
        if (CollUtil.isEmpty(uids)) return Collections.emptySet();
        return uids.stream().map(userSessions::get).filter(Objects::nonNull)
                   .collect(Collectors.toSet());
    }
}
```

**业务规则**：

- `BR-13-040`：房间广播**必须**使用 RocketMQ 广播模式，每个实例消费全量消息。
- `BR-13-041`：`roomId` 作为**顺序消息键**，保证同一房间消息有序。
- `BR-13-042`：本地无订阅者时**快速丢弃**，不产生额外开销。
- `BR-13-043`：**用户级消息**（通知、私信）不广播全量，而是**定向投递**：查 Redis `ml:ws:session:{userId}` 得到目标节点，投递到该节点的专属 Topic（集群模式）或直接 HTTP 调用该节点。

**用户级定向投递（优化）**：

```java
public void sendToUser(Long userId, WsMessage msg) {
    String sessionInfo = redis.opsForValue().get("ml:ws:session:" + userId);
    if (sessionInfo == null) return;                    // 用户不在线
    String nodeId = sessionInfo.split(":")[0];
    if (nodeId.equals(currentNodeId)) {
        // 本机直推
        localRegistry.getSession(userId).sendMessage(msg);
    } else {
        // 投递到目标节点（用 Topic 后缀区分节点，集群消费）
        rocketMQTemplate.syncSend("ml_ws_node_" + nodeId, JSON.toJSONString(msg));
    }
}
```

> **说明**：两种策略结合——**房间广播用 MQ 广播模式**（订阅者多且分散），**用户定向用节点专属 Topic**（精准投递，避免全实例收到后丢弃）。

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-13-007-1` | MQ 不可用 | 降级为**仅本机推送** + 告警（跨实例用户收不到，但本机可用） |
| `EX-13-007-2` | 消息积压 | 告警；WS 广播消息 TTL 短（30s），过期丢弃（实时性优先） |
| `EX-13-007-3` | 实例扩容 | 新实例启动后自动订阅广播组，立即生效 |
| `EX-13-007-4` | 同一用户多实例连接 | 按 `deviceId` 只保留最新；旧节点收到投递时发现会话已失效则丢弃 |
| `EX-13-007-5` | 消息重复投递 | 客户端按 `msgId` 去重 |

**验收标准**：

- `AC-13-007-1`：3 实例部署下，跨实例房间消息 200ms 内触达
- `AC-13-007-2`：实例从 3 扩到 6，消息无丢失、无重复
- `AC-13-007-3`：用户级通知精准投递到用户所在节点
- `AC-13-007-4`：MQ 不可用时本机内用户仍可正常聊天
- `AC-13-007-5`：同一房间消息顺序正确

---

### FR-13-008 消息格式与协议规范 【P0】

> 前后端**必须**严格遵守本协议。协议变更须同步更新 [00 章修订记录](./00-修订记录与术语表.md)。

#### 8.1 通用消息信封

```typescript
interface WsMessage<T = any> {
  type: string;              // 消息类型（点分命名，见下表）
  msgId?: string;            // 服务端消息ID（服务端下发时必填）
  clientMsgId?: string;      // 客户端消息ID（客户端发起时必填，用于 ack）
  roomId?: string;           // 房间消息必填
  ts?: number;               // 客户端发送时间戳
  data: T;                   // 业务载荷
}
```

#### 8.2 消息类型全表

| type | 方向 | 可靠 | 说明 |
|---|---|---|---|
| `connected` | S→C | 否 | 连接建立成功 |
| `ping` / `pong` | 双向 | 否 | 心跳 |
| `auth.expiring` | S→C | 否 | token 即将过期 |
| `error` | S→C | 否 | 通用错误 |
| **房间** | | | |
| `room.join` | C→S | 是 | 加入房间频道 |
| `room.join.ack` | S→C | 是 | 加入成功（含房间快照） |
| `room.leave` | C→S | 否 | 离开房间 |
| `room.member.join` | S→C | 否 | 成员加入通知 |
| `room.member.leave` | S→C | 否 | 成员离开通知 |
| `room.member.update` | S→C | 否 | 成员状态变更（禁言/角色） |
| `room.chat` | 双向 | 是 | 聊天消息 |
| `room.chat.ack` | S→C | 是 | 聊天回执 |
| `room.chat.rejected` | S→C | 是 | 聊天被拒 |
| `room.danmaku` | 双向 | 否 | 弹幕 |
| `room.play` | 双向 | 是 | 播放 |
| `room.pause` | 双向 | 是 | 暂停 |
| `room.switch` | 双向 | 是 | 切歌 |
| `room.seek` | 双向 | 是 | 跳转 |
| `room.sync` | S→C | 是 | 权威状态同步 |
| `room.sync.request` | C→S | 否 | 请求同步 |
| `room.mic.update` | S→C | 是 | 麦位变更 |
| `room.queue.update` | S→C | 是 | 排麦队列变更 |
| `room.notice.update` | S→C | 是 | 公告变更 |
| `room.gift` | 双向 | 是 | 礼物 |
| `room.vote` | 双向 | 是 | 投票切歌 |
| `room.closed` | S→C | 是 | 房间解散 |
| `room.kicked` | S→C | 是 | 被踢出 |
| **通知** | | | |
| `notify.comment` | S→C | 否 | 评论回复 |
| `notify.like` | S→C | 否 | 点赞 |
| `notify.follow` | S→C | 否 | 关注 |
| `notify.message` | S→C | 否 | 私信 |
| `notify.mention` | S→C | 否 | @提醒 |
| `notify.room_invite` | S→C | 否 | 房间邀请 |
| `notify.audit` | S→C | 否 | 审核结果 |
| `notify.report` | S→C | 否 | 举报结果 |
| `notify.system` | S→C | 否 | 系统公告 |
| `notify.unread` | S→C | 否 | 未读数变更 |

#### 8.3 消息大小与编码

- 单条消息 ≤ **64KB**（超出断开连接）。
- 编码 UTF-8，JSON 序列化（`jackson`）。
- 时间戳统一毫秒。
- ID 统一字符串（防 JS 精度丢失）。

#### 8.4 错误消息格式

```json
{
  "type": "error",
  "data": {
    "code": 40005,
    "message": "你已被禁言",
    "context": { "type": "room.chat", "clientMsgId": "uuid-xxx" }
  }
}
```

---

### FR-13-009 背压与过载保护 【P1】

| 项 | 内容 |
|---|---|
| **功能目标** | 防止慢消费者拖垮服务端 |
| **使用角色** | 系统内部 |

**策略**：

| 层面 | 措施 |
|---|---|
| 单连接 | 每连接发送队列上限 200 条，超出丢弃最旧（弹幕类）或断开（可靠消息） |
| 单连接 | 检测发送阻塞（`session.sendMessage` 耗时长）→ 标记慢连接 |
| 实例级 | 连接数上限（默认 10000），超出拒绝新连接（HTTP 503） |
| 实例级 | 消息发送线程池隔离，避免阻塞心跳 |
| 房间级 | 单房间广播频率上限（如 100 条/秒），超出合并或丢弃弹幕 |
| 全局 | MQ 消费积压 > 10 万条时触发降级（关闭非关键广播） |

**业务规则**：

- `BR-13-044`：弹幕类消息在过载时**允许丢弃**（实时性 > 完整性）。
- `BR-13-045`：可靠消息（聊天、控播）**不可丢弃**，慢连接直接断开让其重连补偿。
- `BR-13-046`：发送使用**非阻塞**写入 + 独立线程池，不阻塞 MQ 消费线程。

**验收标准**：

- `AC-13-009-1`：慢消费者不会导致其他用户消息延迟
- `AC-13-009-2`：实例连接数达上限时新连接被拒绝（503）
- `AC-13-009-3`：过载时弹幕被丢弃但聊天不丢

---

### FR-13-010 WebSocket 监控 【P0】

| 指标 | 说明 | 告警阈值 |
|---|---|---|
| `ws_connections_current` | 当前连接数 | 接近上限 80% |
| `ws_connections_total` | 累计连接数 | — |
| `ws_messages_in_total` | 入站消息数 | — |
| `ws_messages_out_total` | 出站消息数 | — |
| `ws_message_latency` | 消息处理延迟 | P99 > 500ms |
| `ws_reconnect_rate` | 重连率 | > 10%/分钟 |
| `ws_handshake_fail_total` | 握手失败数 | > 100/分钟 |
| `ws_slow_connection_total` | 慢连接数 | > 10 |
| `ws_room_broadcast_dropped` | 广播丢弃数 | > 1000/分钟 |

**实现**：Micrometer + Prometheus，Grafana 看板。

---

## 6. 关键时序图

### 6.1 加入房间

```
Client A          WS实例1            Redis           RocketMQ        其他实例
   │                 │                 │                 │              │
   ├─ ws connect ───>│                 │                 │              │
   │                 ├─ 校验token      │                 │              │
   │                 ├─ 注册会话 ─────>│ ml:ws:session   │              │
   │<── connected ───┤                 │                 │              │
   │                 │                 │                 │              │
   ├─ room.join ────>│                 │                 │              │
   │  {roomId}       ├─ 校验房间/黑名单 │                 │              │
   │                 ├─ 加入在线集合 ─>│ ml:room:online  │              │
   │                 ├─ 本地订阅 ──────│                 │              │
   │                 ├─ 读播放态 ─────>│ ml:room:play    │              │
   │<── room.join.ack┤                 │                 │              │
   │  (房间快照)      │                 │                 │              │
   │                 ├─ 广播成员加入 ──┼────────────────>│              │
   │                 │                 │                 ├─ 广播 ──────>│
   │                 │                 │                 │              ├─ 推送给本机房间成员
```

### 6.2 房间控播与同步

```
房主A (实例1)        实例1              Redis          RocketMQ         实例2-用户B
   │                 │                 │                 │                 │
   ├─ room.play ────>│                 │                 │                 │
   │                 ├─ 校验房主权限   │                 │                 │
   │                 ├─ 分布式锁 ─────>│ ml:lock:room:play               │
   │                 ├─ 写权威状态 ───>│ ml:room:play    │                 │
   │                 ├─ INCR version ─>│ ml:room:ver     │                 │
   │                 ├─ 释放锁         │                 │                 │
   │<── room.play ───┤ (回执本机)       │                 │                 │
   │                 ├─ 广播 ──────────┼────────────────>│                 │
   │                 │                 │                 ├─ 广播 ─────────>│
   │                 │                 │                 │                 ├─ 本地推送
   │                 │                 │                 │                 │  计算进度
   │                 │                 │                 │                 │  position + (now-serverTime)
   │                 │                 │                 │                 │  校准播放
   │                 │                 │                 │                 │
   │                 │  [每60s] 兜底同步 ──> 广播 room.sync ──────────────>│
```

### 6.3 断线重连与补偿

```
Client              WS实例            Redis            MySQL
   │                 │                 │                 │
   ├─ 网络中断 ✗     │                 │                 │
   │  (onClose)      ├─ 会话标记失效   │                 │
   │                 ├─ 90s后清理 ────>│ ml:room:online  │
   │                 │                 │                 │
   ├─ 1s 后重连 ────>│                 │                 │
   │  (退避+抖动)     ├─ 校验token      │                 │
   │<── connected ───┤                 │                 │
   │                 │                 │                 │
   ├─ room.join ────>│  (重新加入)      │                 │
   │<── room.join.ack┤                 │                 │
   │                 │                 │                 │
   ├─ GET /room/{id}/messages?sinceId=lastMsgId ─────────> 补偿消息
   │<── 断线期间的消息 ─────────────────────────────────────┤
   │                 │                 │                 │
   ├─ room.sync.request ─>│ 拉取最新播放态 ─>│             │
   │<── room.sync ───┤                 │                 │
```

---

## 7. Nginx 配置要点

```nginx
location /ws {
    proxy_pass http://ws_backend;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

    proxy_read_timeout 300s;        # 必须 > 心跳间隔的 3 倍
    proxy_send_timeout 300s;
    proxy_connect_timeout 10s;

    # WebSocket 长连接不压缩（CPU 考虑）
    proxy_buffering off;
}
```

**负载均衡策略**：

- WS 连接**不需要 sticky**（会话状态存 Redis），可用 `least_conn`（最少连接）均衡。
- 若使用 IP hash 会导致负载不均，**不推荐**。
- 升级为 `wss://` 时，TLS 在 Nginx 终结（性能优于应用层）。

---

## 8. 降级策略

| 场景 | 降级 |
|---|---|
| RocketMQ 不可用 | 仅本机推送（同实例用户可通信）+ 告警 |
| Redis 不可用 | 会话注册降级为本地内存（无法跨实例）；房间状态不可用则"房间功能受限"提示 |
| WebSocket 完全不可用 | 降级为 **HTTP 轮询**（3s 间隔）拉取房间消息与通知（P2，体验差但可用） |
| 单实例过载 | 拒绝新连接，客户端退避重连到其他实例 |

---

## 9. 验收标准汇总

| 编号 | 验收标准 | 优先级 |
|---|---|---|
| `AC-13-100` | 单实例稳定承载 10000 连接 | P0 |
| `AC-13-101` | 同机房消息端到端 P95 ≤ 200ms | P0 |
| `AC-13-102` | 集群部署下房间消息无丢失无重复 | P0 |
| `AC-13-103` | 断线 5s 内自动重连，用户无感 | P0 |
| `AC-13-104` | 可靠消息（聊天/控播）不丢失 | P0 |
| `AC-13-105` | 50 人房间播放进度偏差 ≤ 1.5s | P0 |
| `AC-13-106` | 心跳机制正常，死连接 90s 内清理 | P0 |
| `AC-13-107` | 慢连接不影响其他用户 | P1 |
| `AC-13-108` | MQ 不可用时本机通信仍正常 | P0 |
| `AC-13-109` | 消息格式符合协议规范，无字段缺失 | P0 |

---

[← 上一章：模块03 Redis缓存](./12-模块03-Redis缓存.md) | [返回目录](./README.md) | [下一章：模块05 音乐房 →](./14-模块05-音乐房.md)
