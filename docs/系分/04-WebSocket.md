# 04 · WebSocket 实时通信 — 模块系分

> 对应 PRD：[13-模块04-WebSocket](../PRD/13-模块04-WebSocket.md)
> 上游依赖：模块 01（用户 / 歌曲）、模块 02（前端外壳、登录态、路由）、模块 03（Redis 基础设施、`RateLimitService`、`SensitiveWordSupport`）
> 下游消费：模块 05（音乐房直接复用房间/控播契约）、模块 08（通知中心复用 `t_notification` 与 `notify.*`）、模块 12（推送/指标）

---

## 1. 范围

### 1.1 本次交付（In Scope）

| # | 内容 | 对应 PRD |
|---|---|---|
| 1 | 唯一端点 `GET /ws`（握手即升级）：query 携带 `token` / `deviceId`，握手阶段完成 token、封禁、单 IP / 单实例上限校验 | FR-13-001、§4.2、§4.3 |
| 2 | `connected` 帧下发 `connectionId` / `serverTime` / `heartbeatInterval`（毫秒）/ `maxMessageSize` / `tokenExpireAt`，随后立即下发 `notify.unread` | BR-13-027、§8.2 |
| 3 | 应用层心跳 `ping` / `pong`；服务端 10s 扫一次、空闲 90s 关闭（4002）；`auth.expiring` 提前 5 分钟提示 | FR-13-002、BR-13-006~008、§4.3 |
| 4 | 连接注册表：按设备替换旧连接（1008 `replaced by a newer connection`）、按 IP 计数、按房间索引、节点连接数登记 | BR-13-001、FR-13-007 |
| 5 | 房间加入 / 离开 / 在线集合 / 在线人数；模块 05 未交付前的演示房主策略（首个进房者 SETNX 成为房主） | FR-13-003、D-05 |
| 6 | 房间聊天与弹幕：敏感词拦截、黑名单、禁言剩余时间、频率限制、`clientMsgId` 幂等、ack、广播、异步落库 | FR-13-003 |
| 7 | 控播 `room.play` / `pause` / `seek` / `switch` / `sync.request`：房主校验、版本单调、权威状态写 Redis、ack + 全员广播 | FR-13-004 |
| 8 | 通知：先落库再推送、`notify.*` 类型映射、未读数 DB 为准 + Redis 缓存、HTTP 列表 / 已读 / 全部已读 / 管理端批量推送 | FR-13-005 |
| 9 | 断线补偿：`GET /ws/rooms/{roomId}/messages?sinceId=` 按 messageId 游标拉取；`GET /ws/rooms/{roomId}/playstate` 首屏对齐 | FR-13-006、BR-13-037 |
| 10 | 集群广播：Redis Pub/Sub 按 `room` / `user` 目标投递，订阅端按 `originNodeId` 跳过自己 | FR-13-007、D-01 |
| 11 | 运行指标：连接数、消息进出、握手失败、慢连接、广播丢弃、落库队列积压，`GET /api/v1/ws/stats`（运营/管理员） | FR-13-010 |
| 12 | 前端：`wsCore.ts`（纯规则）/ `wsClient.ts`（I/O）/ `wsStore.ts`（状态）、通知铃铛、演示房间页 `/live/:roomId` | FR-13-003~006 |
| 13 | 测试：零依赖纯函数断言 `web/scripts/test-ws.mjs`；端到端验收脚本 `scripts/verify-04.mjs`（自建 RFC6455 客户端） | — |

### 1.2 明确不做（后置到对应模块）

| 项 | 归属 | 原因 |
|---|---|---|
| 房间 CRUD、密码、满员、房管、麦位、踢人 / 禁言接口 | 模块 05 | 模块 04 只消费房间键；`demoRoomEnabled=true` 期间任意数字房间可进 |
| 点赞 5 分钟聚合为一条通知 | 模块 08 / 12 | 需要聚合窗口与业务事件源，本模块只提供 `notify.*` 通道 |
| 用户通知偏好开关（关闭某类推送） | 模块 08 | `AC-13-005-4` 依赖用户设置表 |
| 私信、@提醒的业务触发 | 模块 08 / 09 | 本模块只提供投递能力 |
| 消息撤回、审核流、举报处理 | 模块 05 / 13 | `t_room_message.status/audit_status` 已留字段，本模块只写 `1/1` |
| RocketMQ | — | 本机无 MQ；用 Redis Pub/Sub 达成同等语义（D-01） |
| WebSocket 不可用时的 3s HTTP 轮询降级 | P2 | PRD 标为 P2；当前 HTTP 补偿接口已具备，前端未做自动轮询 |
| 10000 连接压测、3 实例扩缩容、慢消费者隔离 | 验收 / 运维 | 需要独立压测环境；代码有上限与指标，但本轮不产出实测数字 |
| 逐条消息的审计与全文检索 | 模块 13 | — |

### 1.3 前置条件

- MySQL 8（共享实例）已执行 `sql/schema.sql`，其中包含 `t_room_message`、`t_notification`
- Redis 6379（共享实例）可用；所有键带 `ml:` 前缀，禁止 `FLUSHDB/FLUSHALL`
- 后端 `http://localhost:8081` 可启动；前端 Vite `5173` 已把 `/api/**` 与 `/ws`（`ws: true`）代理到后端
- MySQL root 密码不是默认值，运行时通过 `MYSQL_PASSWORD` 环境变量注入（不落任何文件）

---

## 2. 系分决策（ADR）

| 编号 | 决策 | 理由 | 影响面 |
|---|---|---|---|
| **D-01** | 集群广播用 **Redis Pub/Sub**（Redisson `RTopic`），不引入 RocketMQ | 本机没有 MQ；Pub/Sub 已能覆盖「任一节点发布、所有节点各投本机连接」的需求。可靠消息（聊天/控播）在发布前已落库，最坏情况下由 HTTP 游标补偿；因此 Pub/Sub 的「不持久化」不会造成可靠消息永久丢失。生产替换为 MQ 时只需换 `WsClusterBus` 实现 | `WsClusterBus` / `WsBusSubscriber`；PRD §8「MQ 不可用仅本机推送」由架构天然满足 |
| **D-02** | token 走 **query**（`?token=&deviceId=`），鉴权放在 `HandshakeInterceptor`，不进 Spring Security 过滤器链 | 浏览器原生 WebSocket 不能设置 `Authorization` 头；Security 只认 Bearer。握手拦截器是 WS 生命周期的一部分，失败时能给出正确的 HTTP 握手响应。同时兼容 `Authorization: Bearer` 供服务间/压测客户端使用 | `WsHandshakeInterceptor`、`WsConfig` |
| **D-03** | 握手失败返回真实 HTTP 状态：401 未登录/凭证无效、403 封禁、429 单 IP 超限、503 实例满；响应体仍用统一 `{code,message}` | 客户端在 `onerror` 里拿不到业务错误码，但浏览器 DevTools / 代理能看到状态码；两者结合便于排障。**日志从不打印完整 URI**（带 token） | `WsHandshakeInterceptor` |
| **D-04** | 会话键按设备拆开：`ml:ws:session:{userId}:{deviceId}`，值为 `{nodeId}:{connectionId}`，TTL 90s 心跳续期 | PRD 12 章 key 表原为「每用户一条」，无法表达多设备在线。按设备拆开后，同设备新连接覆盖旧值即可实现 `BR-13-001`，不同 `deviceId` 可共存；关闭时只有值仍属于自己才删除 | `RedisKeys.wsSession`、`WsPresenceService` |
| **D-05** | 模块 05 未交付前用 **演示房主**：`newmusic.ws.demo-room-enabled=true` 时任意登录用户可进任意数字房间，首个进房者 `SETNX ml:room:owner:{roomId}` 成为房主（24h）。生产必须置 `false`，届时 `room.join` fail-closed（40001） | 没有 `t_room` 可校验，开放任意房间是唯一能让 UI 跑起来的方式；用 `SETNX` 而不是「先查再写」避免并发双房主。键名与模块 05 的正式字段隔离，接入时删除即可 | `WsPresenceService.claimOwner`、`application.yml` |
| **D-06** | 可靠消息幂等：`clientMsgId` + Redis `SET NX EX 300`，值先写 `__IN_FLIGHT__`，成功后替换为完整 ack JSON；重复请求原样重放 ack，处理中返回 10005 | 网络重试必须只处理一次（`AC-13-006-3`）。重放的是**当时真实的 ack**，客户端拿到的 `messageId` 与首次一致，不会产生两条消息。`clientMsgId` 缺失时聊天直接拒绝（`missing_client_msg_id`）；控播在无 `clientMsgId` 时按首次处理（兼容脚本） | `WsDedupService`、`MusicWebSocketHandler` |
| **D-07** | 控播状态先 `INCR ml:room:ver:{roomId}`，再 `HSET ml:room:play:{roomId}`；**版本号是唯一权威**，客户端只应用更大的 `version` | 两个键无法原子写。顺序的代价分析：版本涨了而 Hash 仍旧 → 客户端短暂对齐旧进度，下一次 `room.sync` 修正；Hash 新了而版本没涨 → 客户端会忽略新状态，更糟。因此选前者。`playSnapshot` 的 `version` 优先读版本键，缺失才回退 Hash 字段 | `WsPresenceService.savePlayState`、`wsCore.shouldApplyVersion` |
| **D-08** | 通知**先落库、后推送**；落库失败不推送，推送失败只记 WARN。未读数以 DB `COUNT` 为准，Redis 只做缓存（7 天） | `EX-13-005-3`：客户端收到推送就必须能在 HTTP 列表里找到它。未读数不用 `INCR`，因为缓存冷启动时 `INCR` 会从 1 开始、少算历史未读；推送不是热点路径，多一次索引 COUNT 换一致性值得 | `NotificationService` |
| **D-09** | 断线**不做 WS 自动重放**；客户端重连后用 `GET /ws/rooms/{roomId}/messages?sinceId=` 补齐，`sinceId` 用最后一条 `messageId` | WS 重放需要服务端维护每连接游标与巨大缓冲；HTTP 游标天然分页、可重试。雪花 ID 单调可比较，不受时钟回拨影响（`BR-13-037`） | `RoomMessageService.listSince`、`wsStore` |
| **D-10** | 应用层消息上限 65536 **按字符长度**判断（`payload.length()`），容器缓冲按字节；超限先回 error 再以 1009 关闭 | 容器的 64KiB 是字节，业务兜底用字符更宽松（中文 3 字节），保证不会出现「容器先断、客户端收不到 error」。真正的业务长度另有 200（聊天）/50（弹幕）字符限制 | `MusicWebSocketHandler.handleTextMessage`、`WsConfig` |
| **D-11** | 心跳间隔配置用**秒**，`connected` 帧下发**毫秒**（×1000）；容器 `maxSessionIdleTimeout=0`，90s 判定只由应用层扫描负责 | PRD §8.2 示例是 30000ms，配置写 30 更易读。若容器也参与空闲关闭，会出现两个互不知情、阈值不同的计时器，排障时说不清是谁关的 | `WsProperties`、`WsConfig`、`WsSessionRegistry.sweepIdle` |
| **D-12** | 所有 ID 在协议层显式 `toString()`；HTTP 侧由 Jackson `SafeLongSerializer` 把超过 `2^53-1` 的 Long 序列化为字符串 | 雪花 ID 19 位，JS `Number` 会丢精度。WS 消息不经过 Spring MVC 的 ObjectMapper 定制，所以协议层自己保证；时间戳（`Date.now()` 量级）仍是数字，客户端不需要 parseInt | `WsMessages`、`JacksonConfig` |
| **D-13** | 前端把「退避序列、去重、进度推算、漂移分级、version 单调」抽进 `wsCore.ts`（不碰 `window/WebSocket/定时器`），用 Node 原生类型擦除直接 import 做断言 | 这些数字都是 PRD 写死的阈值，纯函数才能逐条对照；I/O 时序留给 `verify-04.mjs` 连真服务端验证。与模块 07 的 `playOrder.ts` 同一套路，零依赖 | `web/src/services/wsCore.ts`、`web/scripts/test-ws.mjs` |
| **D-14** | 同设备新连接注册时**先摘本地索引，再 close 旧连接**（1008），旧连接的 `afterConnectionClosed` 不会误删新会话的 Redis 键 | 顺序反过来会让旧连接的回调把新连接刚写的 `ml:ws:session` 删掉。会话键删除前还比对值，只有仍等于 `{nodeId}:{connectionId}` 才删 | `WsSessionRegistry.register/unregister` |
| **D-15** | 周期 `room.sync` 兜底（60s）**只投本机**，每个节点各自调度；不跨节点发布 | 若每个节点都向广播总线发布，N 个节点会产生 N² 条重复帧。本机调度已能保证每个连接收到自己节点的心跳式对齐 | `MusicWebSocketHandler.periodicRoomSync` |
| **D-16** | 指标用进程内 `AtomicLong` + `/ws/stats`，不引入 Micrometer/Actuator | 本轮没有 Prometheus 端点需求；`WsMetrics.snapshot()` 暴露的字段与 PRD 指标一一对应，后续接 Micrometer 只需在 `increment*` 旁补一次注册。`/stats` 限运营/管理员 | `WsMetrics`、`WsController` |
| **D-17** | 频率限制复用模块 03 的 `RateLimitService`，键为 `ml:limit:ws-chat:user:{userId}:chat` / `ml:limit:ws-danmaku:user:{userId}:danmaku`，窗口 60s | 不另造计数器；模块 03 的滑动窗口已处理并发与过期。聊天 20 条/分、弹幕 10 条/分来自 `WsProperties` | `MusicWebSocketHandler`、`RateLimitService` |
| **D-18** | 敏感词命中**整条拒绝**（`sensitive`），不做 `***` 替换后放行 | PRD `AC-13-003-2` 要求「其他成员看不到」；替换后仍会广播一条内容，且替换算法本身需要审核策略。拒绝原因只回给发送方，不广播 | `SensitiveWordSupport` |

---

## 3. 代码结构

### 3.1 后端新增

```
src/main/java/com/example/newmusic/module/ws/
├── config/
│   ├── WsConfig.java                 # /ws 注册、Origin、容器 64KiB、idle=0
│   ├── WsExecutorConfig.java         # 落库线程池（拆出来避免与 Handler 循环依赖）
│   └── WsProperties.java             # newmusic.ws.*
├── controller/
│   └── WsController.java             # /api/v1/ws/**（stats、通知、补偿、播放态）
├── dto/
│   └── NotificationPushReq.java
├── entity/
│   ├── Notification.java             # 追加型表，不继承 BaseEntity
│   └── RoomMessage.java              # id 为 WS 雪花 messageId（IdType.INPUT）
├── handler/
│   └── MusicWebSocketHandler.java    # 协议主流程 + 90s 扫描 + 周期 sync
├── mapper/
│   ├── NotificationMapper.java
│   └── RoomMessageMapper.java
├── service/
│   ├── NotificationService.java
│   ├── RoomMessageService.java
│   ├── WsBusSubscriber.java
│   ├── WsClusterBus.java
│   ├── WsDedupService.java
│   ├── WsMetrics.java
│   ├── WsPresenceService.java
│   └── WsPushService.java
└── support/
    ├── BusEnvelope.java
    ├── WsHandshakeInterceptor.java
    ├── WsMessages.java
    ├── WsNodeId.java
    ├── WsSession.java
    └── WsSessionRegistry.java
```

基础设施改动：

- `infra/redis/RedisKeys.java`：新增 `ml:ws:*` / `ml:room:*` / `ml:notify:unread:*` 构造器
- `infra/config/JacksonConfig.java`（已有 `SafeLongSerializer`）：HTTP 响应中的雪花 ID 自动转字符串
- `sql/schema.sql`：新增 `t_room_message`、`t_notification`
- `application.yml`：新增 `newmusic.ws.*`（见 §4.7）
- `pom.xml`：`spring-boot-starter-websocket`

### 3.2 前端新增 / 改动

```
web/src/
├── services/
│   ├── wsCore.ts                     # 纯函数：退避/去重/进度/漂移/version/URL
│   └── wsClient.ts                   # 连接、心跳、ack 重试、重连、visibility
├── stores/
│   └── wsStore.ts                    # 房间消息、播放态、通知、未读、乐观消息
├── api/
│   └── ws.ts                         # 通知列表/已读/全部已读/补偿/播放态
├── components/common/
│   ├── NotificationBell.tsx
│   └── NotificationBell.module.less
├── pages/live/
│   ├── DemoRoomPage.tsx              # /live/:roomId 演示房（模块 05 前的联调页）
│   └── DemoRoomPage.module.less
├── layouts/MainLayout.tsx            # 登录后挂 NotificationBell
├── router/index.tsx                  # /live/:roomId
├── types/api.ts                      # NotificationItem / RoomMessageItem 等
└── vite.config.ts                    # '/ws' → ws:true 代理
web/scripts/test-ws.mjs               # 零依赖断言
```

---

## 4. 接口契约

### 4.1 握手

```
GET /ws?token={JWT}&deviceId={optional}
Upgrade: websocket
```

- `deviceId` 缺省为 `default`；只保留 `[A-Za-z0-9_.:-]`，最长 64 字符，其余替换为 `_`
- `token` 也可放在 `Authorization: Bearer ...`（非浏览器客户端）
- 失败响应（不建立 WS）：

| 场景 | HTTP | `code` |
|---|---|---|
| 缺少 token | 401 | 40101 |
| token 无效 / 类型不对 | 401 | 40102 |
| token 过期 | 401 | 40103 |
| 账号封禁 | 403 | 20005 |
| 同 IP 连接数 ≥ 20 | 429 | 10004 |
| 实例连接数 ≥ 10000 | 503 | 90001 |

### 4.2 信封

```json
{ "type": "room.chat", "msgId": "1840…", "clientMsgId": "c-1",
  "roomId": "1234567890123", "ts": 1760000000000, "data": { } }
```

- 服务端主动业务帧一律带 `msgId`（字符串雪花）；`connected` / `pong` / `*.ack` / `error` 不带
- `ack` 在**顶层**回带 `clientMsgId`
- 房间业务帧在顶层带 `roomId`（字符串），`data` 内也带一份
- 时间戳均为毫秒数字

### 4.3 入站消息

| `type` | `data` | 说明 | 是否需 `clientMsgId` |
|---|---|---|---|
| `ping` | `{ts}` | 心跳；回 `pong`（回显 `ts` + `serverTime`），续期会话/房间在线，可能触发 `auth.expiring` | 否 |
| `room.join` | `{roomId}` | 加入房间；回 `room.join.ack` | 推荐（ack 关联） |
| `room.leave` | — | 离开；广播 `room.member.leave` | 否 |
| `room.chat` | `{content}` | 聊天；回 `room.chat.ack` + 广播 `room.chat` | **是** |
| `room.danmaku` | `{content}` | 弹幕；广播 `room.danmaku`（含发送方） | 否 |
| `room.switch` | `{songId, position?}` | 切歌并播放；房主 | 是 |
| `room.play` | `{songId?, position?}` | 播放；房主 | 是 |
| `room.pause` | `{position?}` | 暂停；房主 | 是 |
| `room.seek` | `{position, isPlaying?}` | 跳转；房主 | 是 |
| `room.sync.request` | — | 拉取权威播放态；回 `room.sync` | 否 |

### 4.4 出站消息

| `type` | 触发 | 关键字段 |
|---|---|---|
| `connected` | 连接建立 | `connectionId`、`serverTime`、`heartbeatInterval`(ms)、`maxMessageSize`、`tokenExpireAt` |
| `pong` | 收到 `ping` | `ts`（回显）、`serverTime` |
| `auth.expiring` | 剩余有效期 ≤ 300s（每次连接只发一次） | `expireAt`、`remainingSeconds` |
| `room.join.ack` | `room.join` | `roomId`、`userId`、`nickname`、`ownerId`、`isOwner`、`onlineCount`、`snapshot`、`serverTime`；顶层回 `clientMsgId` |
| `room.member.join` / `room.member.leave` | 房间成员变化 | `roomId`、`userId`、`nickname`、`onlineCount` |
| `room.chat` | 他人发送 | `messageId`、`roomId`、`userId`、`nickname`、`avatar`、`content`、`messageType=1`、`createTime`；**发送方不收** |
| `room.chat.ack` | 自己发送成功 | `messageId`、`roomId`、`createTime`、`messageType=1`；顶层回 `clientMsgId` |
| `room.chat.rejected` | 聊天被拒 | `reason`、`roomId?`、`remainingSeconds?`、`retryAfterSeconds?`；顶层回 `clientMsgId` |
| `room.danmaku` | 弹幕发送（含自己） | 同 `room.chat`，`messageType=2` |
| `room.danmaku.rejected` | 弹幕被拒 | 同聊天拒绝 |
| `room.play` / `pause` / `seek` / `switch` | 控播成功 | 完整 `snapshot`：`roomId`、`songId`、`songName`、`artistName`、`cover`、`duration`、`position`、`isPlaying`、`operatorId`、`version`、`serverTime`；广播给**全员含操作者** |
| `*.ack`（控播） | 控播成功 | `roomId`、`version`、`serverTime`；顶层回 `clientMsgId` |
| `room.sync` | `room.sync.request` / 加入时快照 / 60s 兜底 | 同控播快照 |
| `notify.unread` | 连接建立、通知落库后、已读后 | `unread` |
| `notify.system` / `comment` / `like` / `follow` / `message` / `mention` / `room_invite` / `audit` / `report` | 通知落库后 | `id`、`notifyType`、`title`、`content`、`fromUserId`、`fromNickname`、`fromAvatar`、`targetType`、`targetId`、`isRead`、`createTime` |
| `error` | 任何协议错误 | `{code, message, context?}`；`context.type` / `context.clientMsgId` |

### 4.5 业务错误码

| 场景 | code | 说明 |
|---|---|---|
| 未知 `type` | 10001 | `error.context.type` 带回原类型 |
| JSON 解析失败 | 10002 | 连接保持 |
| 重复 `clientMsgId` 且首次仍在处理中 | 10005 | 稍后重试即可 |
| 仅支持文本帧（二进制） | 10007 | 连接保持 |
| 参数缺失/非法 | 10001 | 如缺少 `roomId` |
| 未加入房间 | 40001 | 房间消息 / 控播前 |
| 被拉黑 | 40004 | |
| 被禁言 | — | 聊天走 `room.chat.rejected reason=muted` + `remainingSeconds` |
| 非房主控播 | 40006 | `AC-13-004-4` |
| 房间未开启演示模式 | 40001 | 生产 `demo-room-enabled=false` |
| 消息超过 64KiB | 10001 + close 1009 | 先 error 再关闭 |
| 心跳超时 | close 4002 | 90s |
| 同设备被替换 | close 1008 | reason `replaced by a newer connection` |

`room.chat.rejected` 的 `reason`：`not_in_room` / `empty` / `too_long` / `blacklisted` / `muted` / `rate_limited` / `sensitive` / `missing_client_msg_id`。

### 4.6 HTTP 接口（`/api/v1/ws`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/stats` | 运行指标；`ROLE_OPERATOR` / `ROLE_ADMIN` |
| GET | `/notifications?pageNum=&pageSize=` | 通知分页，按 `create_time DESC, id DESC` |
| GET | `/notifications/unread` | `{unread}` |
| PUT | `/notifications/{id}/read` | 幂等；非本人通知按不存在处理 |
| PUT | `/notifications/read-all` | `{changed}` |
| POST | `/admin/notifications/push` | `{userIds, notifyType, title, content, targetType, targetId}`；运营/管理员 |
| GET | `/rooms/{roomId}/messages?sinceId=&limit=` | 断线补偿，`sinceId` 为 messageId 游标，`limit` 默认 50 |
| GET | `/rooms/{roomId}/playstate` | 权威播放态快照 |

### 4.7 Redis 键

| Key | 类型 / 值 | TTL | 写入方 |
|---|---|---|---|
| `ml:ws:session:{userId}:{deviceId}` | string `{nodeId}:{connectionId}` | 90s，心跳续期 | 模块 04 |
| `ml:ws:node:{nodeId}` | string 连接数 | 60s，30s 续期 | 模块 04 |
| `ml:ws:dedup:{userId}:{clientMsgId}` | string `__IN_FLIGHT__` 或 ack JSON | 300s | 模块 04 |
| `ml:notify:unread:{userId}` | string 未读数 | 7d | 模块 04 |
| `ml:room:online:{roomId}` | set userId | 无 | 模块 04（模块 05 接管） |
| `ml:room:online_count:{roomId}` | string 人数 | 无 | 模块 04 |
| `ml:room:user:{userId}` | string roomId | 90s | 模块 04 |
| `ml:room:play:{roomId}` | hash：songId/songName/artistName/cover/duration/position/isPlaying/operatorId/version/serverTime | 无 | 模块 04（模块 05 接管） |
| `ml:room:ver:{roomId}` | string 版本号（INCR） | 无 | 模块 04 |
| `ml:room:mute:{roomId}` | hash userId → 截止毫秒 | 无（按值判断） | 模块 05 |
| `ml:room:blacklist:{roomId}` | set userId | 无 | 模块 05 |
| `ml:room:owner:{roomId}` | string userId | 24h | **仅演示模式**，模块 05 接入后删除 |
| `ml:limit:ws-chat:user:{userId}:chat` | 模块 03 限流结构 | 60s 窗口 | `RateLimitService` |
| `ml:limit:ws-danmaku:user:{userId}:danmaku` | 同上 | 60s 窗口 | `RateLimitService` |

### 4.8 数据表

- `t_room_message`：`id`（雪花 messageId）、`room_id`、`user_id`、`message_type`、`content`、`extra` JSON、`audit_status`、`status`、`create_time(3)`；索引 `(room_id, create_time)`
- `t_notification`：`id`（雪花）、`user_id`、`notify_type`、`title`、`content`、`from_user_id`、`from_nickname`、`from_avatar`、`target_type`、`target_id`、`is_read`、`read_time`、`create_time(3)`；索引 `(user_id, is_read, create_time)`、`(user_id, notify_type, create_time)`

两张表都是**追加型**，实体不继承 `BaseEntity`（没有 `update_time/deleted`）。

---

## 5. 关键时序

### 5.1 连接建立

```
Client                    Interceptor                 Handler/Registry
  │ GET /ws?token&deviceId ──►│
  │                          │ parse JWT / ban / IP / capacity
  │       101 Switching ─────│──────────────────────────►│
  │                          │            register(session)
  │◄──── connected ──────────│──────────────────────────│  (connectionId…)
  │◄──── notify.unread ──────│──────────────────────────│  (DB 未读数)
```

同 `deviceId` 已存在时：`registry.register` 先摘旧索引，再以 1008 关闭旧连接；新连接正常收到 `connected`。

### 5.2 可靠聊天与重试

```
A ── room.chat {clientMsgId:c1} ──► Handler
                                     ├─ 校验房间/内容/黑名单/禁言/限流/敏感词
                                     ├─ SET NX ml:ws:dedup:A:c1 = __IN_FLIGHT__
                                     ├─ 生成 messageId（雪花）
                                     ├─ INSERT t_room_message（异步）
                                     ├─ SET dedup = ack JSON
                                     ├─ 投 ack 给 A
                                     └─ 广播 room.chat 给房间其他人
A ── 重发 c1 ──► 读到 ack JSON → 原样回放，不再落库/广播
```

### 5.3 断线与补偿

```
Client 断线 → 指数退避（1/2/4/8/16/30s + 0~1s 抖动，10 次上限）
  → 重连 → connected + notify.unread
  → room.join → room.join.ack（含 snapshot）
  → GET /rooms/{id}/messages?sinceId=最后一条 messageId
  → 合并到消息列表（按 messageId 去重）
```

### 5.4 控播

```
Owner ── room.switch {songId, clientMsgId} ──► 校验房主 / 幂等占位
                                              ├─ requirePlayable(songId)
                                              ├─ INCR ml:room:ver
                                              ├─ HSET ml:room:play
                                              ├─ ack {version, serverTime}
                                              └─ 广播 room.switch {完整 snapshot}
Client 收到广播：incoming.version > current.version ? 应用 : 忽略
```

### 5.5 通知在线 / 离线

```
调用方 → NotificationService.createAndPush
          ├─ INSERT t_notification
          ├─ COUNT 未读 → SET ml:notify:unread（7d）
          ├─ push notify.<type>
          └─ push notify.unread
离线用户 → 上线 connected 时收到 notify.unread
          → 打开通知列表 → GET /ws/notifications（DB）
```

---

## 6. 前端行为

- `wsClient.ts`
  - `connected` 到达后按 `heartbeatInterval` 起定时器发 `ping`；10s 未收到 `pong` 或连续 2 次缺失则主动关闭并重连
  - 断线重连 `reconnectDelay(attempt)`：1/2/4/8/16/30s 封顶，每次 +0~999ms 抖动，10 次后停止
  - 页面从后台切回前台（`visibilitychange`）且未连接时立即重连并重置退避
  - 可靠消息（聊天/控播）发送后等 ack 5s，超时重发，最多 2 次；`clientMsgId` 只在首次生成
  - 收到 `auth.expiring` 走 token 刷新并重连（`isAuthExpiring` 也做本地兜底）
- `wsStore.ts`
  - 乐观消息用 `clientMsgId` 作为临时 `messageId`，收到 ack 时替换为真实 `messageId`；被拒时移除
  - 房间广播按 `msgId` 去重（`MessageDeduper` 容量 500）
  - 播放态只应用更大的 `version`；`computeServerPosition` 由快照推算当前位置
  - `notify.*` 合并进通知列表与未读数；`room.member.*` 只更新在线人数
- `NotificationBell`：未读徽标、下拉列表、单条/全部已读
- `DemoRoomPage`（`/live/:roomId`）：模块 05 交付前的联调节点；加入房间、聊天/弹幕、控播按钮、播放态展示

---

## 7. 任务清单

### P0（本模块必须完成）

- [x] T401 `pom.xml` 引入 `spring-boot-starter-websocket`；`WsConfig` 注册 `/ws`、Origin 白名单、容器 64KiB、`maxSessionIdleTimeout=0`、`wsMessageExecutor`（2/8/2000 + CallerRuns）
- [x] T402 `WsProperties` + `application.yml` 的 `newmusic.ws.*`（心跳、上限、限流、幂等、TTL、演示开关）
- [x] T403 `WsHandshakeInterceptor`：query/Bearer token、封禁、实例/IP 上限、401/403/429/503、日志脱敏；`deviceId` 清洗
- [x] T404 `WsSessionRegistry` / `WsSession` / `WsNodeId`：连接索引、同设备替换（1008）、房间索引、IP/总数计数、90s 扫描（4002）
- [x] T405 `WsPresenceService`：会话/在线集合/在线人数/播放态/版本/房主/禁言/黑名单/节点负载；`savePlayState` 先 INCR 再 HSET
- [x] T406 `WsClusterBus` / `WsBusSubscriber` / `BusEnvelope`：Redis Pub/Sub，按 `room`/`user` 投递，跳过 `originNodeId`
- [x] T407 `WsPushService`：`sendToUser` / `sendToRoom` / `sendRoomMessage` / `sendAck` / `sendError` / `replayRaw`；本机投递与跨节点发布
- [x] T408 `WsDedupService`：`SET NX EX`、`__IN_FLIGHT__`、ack 重放、失败释放
- [x] T409 `MusicWebSocketHandler`：`connected`、`ping/pong`、`room.join/leave`、`room.chat/danmaku`、控播、`room.sync.request`、`auth.expiring`、异常收口
- [x] T410 `RoomMessageService` / `NotificationService` / `Notification` / `RoomMessage` / Mapper：异步落库、先落库后推送、未读缓存、已读
- [x] T411 `WsController` + `NotificationPushReq`：stats、通知列表/未读/已读/全部已读/管理端推送、消息补偿、播放态
- [x] T412 `sql/schema.sql` 新增 `t_room_message` / `t_notification`
- [x] T413 `WsMetrics` + `/ws/stats`：连接总数、消息进出、握手失败、慢连接、广播丢弃、队列积压
- [x] T414 `web/src/services/wsCore.ts`：退避序列、抖动、`MessageDeduper`、`computeServerPosition`、`driftAction`、`shouldApplyVersion`、`isAuthExpiring`、`buildWsUrl`
- [x] T415 `web/src/services/wsClient.ts`：连接、心跳、ack 重试、指数退避、visibility、auth.expiring
- [x] T416 `web/src/stores/wsStore.ts`：房间消息、乐观渲染、去重、version 单调、通知/未读、成员人数
- [x] T417 `web/src/api/ws.ts` + `types/api.ts`：通知与补偿接口、`NotificationItem` / `RoomMessageItem`
- [x] T418 `NotificationBell` 并挂到 `MainLayout`（登录后）
- [x] T419 `DemoRoomPage` + `/live/:roomId` 路由 + Vite `/ws` 代理（`ws:true`）
- [x] T420 `web/scripts/test-ws.mjs`：**2026-10-18 实测 `npm run test:ws` 73 项全通过 / 0 失败**；`npx tsc --noEmit` 无输出
- [x] T421 `scripts/verify-04.mjs`：自建 RFC6455 客户端（可设 `X-Forwarded-For`、读取握手状态码与 close code），覆盖握手、心跳、房间、聊天、控播、通知、补偿、Redis 键、指标；`node --check` 通过

> **T401~T421 完成记录**
> - 后端按「Handler 只做协议编排」拆分：会话表、Redis 状态、广播、落库、限流、敏感词各归其类，
>   单条坏消息不会终止连接（外层 `catch` 统一回 error）。
> - `WsConfig` 把容器空闲超时设为 0，90s 判定只留给应用层——避免两个阈值不同的计时器互相打架（D-11）。
> - 通知未读数不用 `INCR`：缓存冷启动时会从 1 开始、少算历史未读；改为 DB `COUNT` 后写缓存（D-08）。
> - 集群广播选 Redis Pub/Sub 而非 MQ（本机无 MQ），可靠消息靠「先落库 + HTTP 游标」兜底（D-01/D-09）。
> - 前端把 PRD 写死的数字全部收进 `wsCore.ts`，`test-ws.mjs` 用 Node 24 原生类型擦除直接 import，零依赖。
> - **编译证据（2026-10-18）**：`mvn -q -DskipTests compile` 退出码 0（JDK 17 / Maven 3.9.12）。
> - **启动时抓到并修掉一处真 bug（R-15）**：`WsConfig` 构造注入 `MusicWebSocketHandler`，
>   Handler 又需要 `WsConfig` 内定义的 `wsMessageExecutor`，形成构造器循环依赖，
>   Spring Boot 2.6 直接拒绝启动。已把线程池 Bean 拆到 `WsExecutorConfig`，见 §3.1。
> - **建表与启动（2026-10-18）**：只执行了 `sql/schema.sql` 中 `t_room_message` / `t_notification`
>   两段 DDL（`CREATE TABLE IF NOT EXISTS`），未触碰共享实例上的 `emp_db` / `music_db`。
>   首次启动失败并暴露 R-15（循环依赖），拆出 `WsExecutorConfig` 后 `Started NewmusicApplication in 5.097 seconds`。

> **T422 运行时验收记录（2026-10-18）**
> - 第一轮：`node scripts/verify-04.mjs` → **通过 134 / 失败 0 / 跳过 4**。
> - 第二轮：`node scripts/verify-04.mjs idle` → **通过 135 / 失败 0 / 跳过 3**，
>   新增的正是「90s 无心跳 → close 4002」。跳过项只剩跨实例、浏览器行为、ACK 超时重发。
> - 覆盖到的硬证据：握手 401/40102、封禁 403/20005、单 IP 第 21 条 429/10004、同设备 1008、
>   多设备会话键、入房 ack/snapshot、聊天 ack+广播+幂等+7 种拒绝原因、20 条/分限流、
>   控播 40006、版本递增与幂等、Redis 播放态、通知在线/离线/已读、`sinceId` 补偿、
>   `ml:*` 键与 TTL、`ranking:*` 数量不变（9 → 9）、`/ws/stats` 指标与 403/40301。
> - 脚本在 `finally` 中删除了本轮写入的 `t_room_message` / `t_notification` 行并复查为 0。

### P1（可后置）

- [x] T422 运行时验收：建表 → 启动后端 → `node scripts/verify-04.mjs`，回填 §8 的证据 —— **2026-10-18 实测两轮：`通过 134 / 失败 0 / 跳过 4`；加 `idle` 参数后 `通过 135 / 失败 0 / 跳过 3`**（含 90s 心跳超时 4002）。建表与启动过程中抓到并修掉循环依赖（R-15）
- [ ] T423 浏览器走查：`/live/:roomId` 双标签页聊天/弹幕、控播、通知铃铛、断网重连、token 过期提示
- [ ] T424 压测与集群验收：10000 连接（AC-13-100）、3 实例跨节点（AC-13-102）、慢消费者隔离（AC-13-107）
- [ ] T425 通知偏好开关与点赞聚合（模块 08）
- [ ] T426 慢消费者发送队列 / 弹幕丢弃策略（PRD FR-13-009，P1）

### 待办：本轮停止点（2026-10-18）

> 单实例运行时验收已完成，**剩下的都需要额外环境或人工操作**。

- [x] ~~建表 + 启动后端 + `verify-04.mjs`~~：已完成（134/0/4；idle 模式 135/0/3）。
      建表只执行了 `sql/schema.sql` 中 `t_room_message` / `t_notification` 两段，
      未改动共享实例上的其它库。
- [ ] **T423 浏览器走查**：需要前端 `npm run dev` 与两个登录账号，覆盖
      `/live/:roomId` 双标签页聊天/弹幕、控播、通知铃铛、断网重连、token 过期提示、
      页面切回前台立即重连。`verify-04.mjs` 只验证协议与后端行为，不验证 UI。
- [ ] **T424 多实例/压测**：起第二个实例（不同 `WS_NODE_ID`、端口 8082）验证跨节点投递；
      10000 连接与 50 人长跑属压测环境项。
- [ ] **T426 慢消费者隔离**（R-05）与弹幕过载丢弃（PRD FR-13-009，P1）。

---

## 8. 验收核对（AC 映射）

状态说明：✅ 已有自动化证据（含 2026-10-18 的运行时验收）；⚠️ 功能覆盖但时延/浏览器行为未验，或存在已知偏差；⛔ 明确后置或本轮未验证。

| AC | 验证方式 | 状态 |
|---|---|---|
| AC-13-001-1 无 token / 无效 token 被拒 | `verify-04.mjs` §01：401 + 40101 / 40102 | ✅ |
| AC-13-001-2 封禁用户被拒 | §01：写 `ml:auth:userban:{uid}` → 403 + 20005 | ✅ |
| AC-13-001-3 同 deviceId 旧连接关闭 | §03：旧连接收到 1008，reason 匹配 `replaced by a newer` | ✅ |
| AC-13-001-4 1s 内收到 `connected` | §02：`openWs` 等待首帧；字段与时差校验 | ✅ |
| AC-13-001-5 单实例 10000 连接 | ⛔ 需压测环境（T424） | ⛔ |
| AC-13-002-1 30s ping/pong | §02：ping → pong 回显 `ts`；`connected.heartbeatInterval=30000` | ✅ |
| AC-13-002-2 90s 无心跳关闭 | §11（`idle` 模式）：连接在 115s 内收到 close 4002 | ✅ |
| AC-13-002-3 心跳不产生 INFO 日志 | 代码审查：`onPing` 无业务日志；`grepLog` 只查 ASCII 锚点 | ✅ 静态 |
| AC-13-003-1 200ms 内收到 | §05：B 确认收到 A 的广播；端到端时延未测量 | ⚠️ 功能 ✅ / 时延未测 |
| AC-13-003-2 敏感词拦截 | §05：命中敏感词 → `reason=sensitive`，同房其他人收不到 | ✅ |
| AC-13-003-3 禁言提示剩余时间 | §05：写 `ml:room:mute` → `muted` + `remainingSeconds` ≈120 | ✅ |
| AC-13-003-4 乐观渲染 + ack | `wsStore` 已实现临时消息替换；浏览器走查未做 | ⚠️ 逻辑已实现，未走查 |
| AC-13-003-5 顺序与 messageId 一致 | §08：补偿结果按雪花 ID 升序（BigInt 比较）；单实例投递有序 | ✅ 单节点 |
| AC-13-004-1 50 人 10 分钟偏差 ≤1.5s | ⛔ 需 50 客户端长跑（T424） | ⛔ |
| AC-13-004-2 新成员 500ms 对齐 | §04/§06：`room.join.ack.snapshot` + `room.sync` 返回权威版本与进度 | ✅ 功能 / 时延未测 |
| AC-13-004-3 旧 version 忽略 | `test-ws.mjs` 7 条 `shouldApplyVersion` 断言 | ✅ |
| AC-13-004-4 普通成员不能控播 | §06：成员 `room.play` → 40006，context 带 clientMsgId | ✅ |
| AC-13-004-5 房主切歌全员 500ms | §06：`room.switch` 广播 `songId/songName/isPlaying/operatorId`，B 同步收到 | ✅ 功能 / 时延未测 |
| AC-13-005-1 评论回复 500ms 推送 | §07：管理端推送 → `notify.system` + `notify.unread` | ✅ 功能 / 时延未测 |
| AC-13-005-2 离线用户 HTTP 拉取 | §07：离线后列表含该通知，重连只收 `notify.unread` 不重放 | ✅ |
| AC-13-005-3 点赞 5 分钟聚合 | ⛔ 模块 08 | ⛔ |
| AC-13-005-4 关闭类型后不推送 | ⛔ 模块 08（用户偏好） | ⛔ |
| AC-13-006-1 断网 5s 恢复不丢消息 | `test-ws.mjs` 退避参数 ✅；§08 补偿链路 ✅；真实断网未注入 | ⚠️ |
| AC-13-006-2 指数退避 + 抖动 | `test-ws.mjs`：序列、封顶、10 次上限、500 次随机抖动 | ✅ |
| AC-13-006-3 同一 clientMsgId 只处理一次 | §05：重发同一 c1 → 同一 ack、不再广播；Redis 幂等键 TTL ≤300 | ✅ |
| AC-13-006-4 断线 1 分钟可完整拉取 | §08：`sinceId` 游标只返回断线期间的 c2/c3 | ✅ |
| AC-13-006-5 切回前台 1s 内重连 | `wsClient` 已监听 `visibilitychange`；浏览器走查未做 | ⚠️ |
| AC-13-007-1 3 实例跨节点 200ms | Redis Pub/Sub 已实现；⛔ 未部署多实例 | ⛔ |
| AC-13-007-2 3→6 扩容无丢失/重复 | 设计上由 Pub/Sub + 去重覆盖；⛔ 未实测 | ⛔ |
| AC-13-007-3 用户级通知精准投递 | `ml:ws:session` + `sendToUser`；§07 单实例验证通过，跨节点未验 | ⚠️ |
| AC-13-007-4 MQ 不可用本机仍可聊 | 无 MQ 依赖；Pub/Sub 失败只记 WARN，本机投递照常 | ✅ 设计上 |
| AC-13-007-5 同房间顺序正确 | 单实例按到达顺序投递；§08 以雪花 ID 校验升序 | ✅ 单节点 |
| AC-13-009-1 慢消费者不影响他人 | `WsPushService` 单连接发送超时计数；未做隔离队列 | ⚠️ 见 R-05 |
| AC-13-009-2 连接达上限拒绝（503） | §01：单 IP 20 → 429；实例 10000 上限的 503 路径未压测 | ⚠️ |
| AC-13-009-3 过载时弹幕丢弃、聊天不丢 | 未实现丢弃策略 | ⛔ P1（T426） |
| AC-13-100 单实例 10000 连接 | ⛔ T424 | ⛔ |
| AC-13-101 P95 ≤200ms | ⛔ 未测量 | ⛔ |
| AC-13-102 集群无丢失无重复 | 设计已实现；⛔ 未实测 | ⛔ |
| AC-13-103 断线 5s 自动重连 | 退避逻辑 ✅；浏览器走查未做 | ⚠️ |
| AC-13-104 可靠消息不丢 | 落库 + ack + 幂等 + 游标补偿；§05/§08 全部通过 | ✅ 单节点 |
| AC-13-105 50 人偏差 ≤1.5s | `computeServerPosition` / `driftAction` 单测 ✅；50 人实测 ⛔ | ⚠️ |
| AC-13-106 心跳正常、90s 清理 | §02 ping/pong + §11 idle 4002 | ✅ |
| AC-13-107 慢连接不影响他人 | 见 R-05 | ⚠️ |
| AC-13-108 MQ 不可用本机通信正常 | 同 AC-13-007-4 | ✅ 设计上 |
| AC-13-109 协议格式无字段缺失 | §02/§04/§05/§06/§07：connected、msgId、ack、error、notify 结构 | ✅ |

---

## 9. 风险与已知偏差

| 编号 | 项 | 说明 | 处置 |
|---|---|---|---|
| R-01 | 验收只覆盖单实例功能路径 | 2026-10-18 的运行时验收（134/0/4，idle 模式 135/0/3）验证了全部协议与单节点行为，但**没有**真实断网注入、没有多实例、没有压测，也没有浏览器走查；`AC-13-003-1/004-5/005-1` 的 200~500ms 时延只验了「收到」而非「多久收到」 | §8 中相关项保持 ⚠️ 或 ⛔；T423/T424 补齐。**不要用单实例功能通过去替代时延/集群验收** |
| R-02 | Redis Pub/Sub 不持久化 | 节点订阅断开期间发布的消息会丢；`EX-13-007-2` 的「无丢失」靠落库 + HTTP 游标补偿，而不是总线本身 | 可靠消息一律先落库；总线路由只负责实时性。生产替换 MQ 时保留同一接口 |
| R-03 | 演示房主是临时策略 | `ml:room:owner:*` 只在 `demo-room-enabled=true` 生效，任意数字房间可进，且 24h 后房主键过期、首个再进房者接管 | 生产必须 `false`；模块 05 用 `t_room.owner_id` 取代。**不能把演示房主当成权限模型** |
| R-04 | 单条消息 64KiB 判断用字符数 | 容器按字节、应用层按 `String.length()`；中文消息的字节上限实际更早触发（约 21k 汉字） | D-10 的取舍：宁可容器先拒，也要保证业务 error 帧能发出；业务长度另有 200/50 限制，正常路径不会接近 |
| R-05 | 慢消费者未隔离 | `WsPushService` 对单连接同步 `sendMessage`，超时只计数并关闭；没有独立发送队列，一个极慢连接可能拖慢同节点广播线程 | 当前 `spring-websocket` 默认单线程发送、`ConcurrentWebSocketSessionDecorator` 未启用。T426 接入发送缓冲/丢弃策略前，`AC-13-009-1/107` 不应打勾 |
| R-06 | 频率限制在幂等判断之前 | 同一 `clientMsgId` 的重试也会消耗一次限流配额（重试 2 次 = 3 次配额）。高频重试可能先触发 `rate_limited` 而不是重放 ack | 影响有限（配额 20/分）；若后续出现「重试被限流」的现场，把 dedup 判断前移到限流之前 |
| R-07 | 控播 ack 只有 `version` 等元数据 | 完整 `snapshot` 在广播帧里，发送方也会收到广播；若客户端只依赖 ack 而不处理自己的广播，会缺 `position/songId` | `wsStore` 同时消费 ack 与广播；文档明确「广播含发送方」 |
| R-08 | `deviceId` 缺省为 `default` | 同一账号多个不传 `deviceId` 的客户端会互相顶掉（1008） | 前端 `wsClient` 始终生成并持久化 `deviceId`；脚本/第三方客户端需自带 |
| R-09 | 通知未读数与列表可能短暂不一致 | 推送后先 `COUNT` 再写缓存；两个并发通知的 COUNT/写缓存可能交错，最终值仍由下一次刷新修正 | 未读数是 UI 提示而非账务数据；已读操作同样以 DB 为准刷新 |
| R-10 | `t_room_message` 异步落库 | 实时广播先于 INSERT 完成；断线补偿在同一毫秒内可能查不到刚广播的消息 | 游标补偿以「最后一条已收到」为起点，客户端本地已有该消息；`EX-13-003-6` 允许落库失败只影响补偿 |
| R-11 | 无 WS 轮询降级 | PRD §8 的 3s HTTP 轮询是 P2，未实现 | 前端断线时依赖重连与 HTTP 补偿；完全不可用时聊天不可用，这是明确的后置项 |
| R-12 | `/ws/stats` 指标是进程内值 | 多实例部署时每个节点各看各的，没有聚合 | 生产接 Prometheus 时按 `nodeId` 打标签聚合；当前 `nodeId` 已在返回中 |
| R-13 | 演示房页面不是模块 05 的正式 UI | `/live/:roomId` 只用于联调，样式与交互都很薄 | 模块 05 交付后由正式房间页替换；`wsStore` 与协议不变 |
| R-14 | `verify-04.mjs` 依赖共享 Redis/MySQL | 会写 `ml:room:*`、`ml:ws:*`、`ml:notify:*`，并插入两张表的数据；脚本在 `finally` 中清理自己创建的键与行，且复查 `ranking:*` 数量不变 | 所有键带 `ml:` 前缀；SQL 一律 `newmusic.` 限定；**禁止 FLUSHDB**。若脚本中途被杀，需手工按 `ROOM` 删除残留 |
| R-15 | **`WsConfig` 与 Handler 构造器循环依赖**（已修） | `WsConfig` 需要 Handler 注册端点，Handler 需要 `@Bean wsMessageExecutor`，而该 Bean 原先定义在 `WsConfig` 内。`mvn spring-boot:run` 启动时报 `BeanCurrentlyInCreationException` / `APPLICATION FAILED TO START`。编译期发现不了，只有真正启动才暴露 | 已修（2026-10-18）：线程池移到独立的 `WsExecutorConfig`。`WsConfig` 只保留端点注册与容器参数。之后启动正常（见 §7 运行记录） |

---

## 10. 变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-10-18 | v1.0 | 首次编写。记录模块 04 的实现决策（D-01~D-18）、协议/HTTP/Redis 契约、任务清单 T401~T426、AC-13-001~109 映射与 14 条风险。静态证据：`npm run test:ws` 73 项通过、`npx tsc --noEmit` 无输出、`mvn -q -DskipTests compile` 退出码 0、`node --check scripts/verify-04.mjs` 通过。运行时验收尚未执行 |
| 2026-10-18 | v1.1 | ①**建表并完成运行时验收（T422）**：`verify-04.mjs` 两轮 **134/0/4** 与 **135/0/3**（idle 模式含 90s → 4002），§8 中 20 项由 ☐ 改为 ✅，其余未验项按实际覆盖改写为 ⚠️/⛔；②**抓到并修掉 R-15**：`WsConfig` 构造注入 Handler、Handler 又依赖 `WsConfig` 内定义的线程池，Spring Boot 2.6 启动即拒绝（`BeanCurrentlyInCreationException`）。线程池拆到 `WsExecutorConfig`；③R-01 由「运行时未执行」改写为「只覆盖单实例功能路径」，明确 200/500ms 时延、真实断网、多实例与浏览器行为仍未验；④§7 停止点更新为只剩 T423 浏览器走查、T424 压测/多实例、T426 慢消费者隔离 |
