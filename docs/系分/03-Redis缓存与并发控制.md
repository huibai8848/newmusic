# 模块 03 系分：Redis 缓存与并发控制

> 对应 PRD：[`12-模块03-Redis缓存.md`](../PRD/12-模块03-Redis缓存.md)
> 前置模块：01（曲库 CRUD，已交付）、07（播放与歌词，已交付）
> 本文档沿用「spec 模式」：先定契约与决策，再实现，实现期回填证据。

---

## 1. 范围

### 1.1 本次交付（In Scope）

| 编号 | FR | 本次落地内容 |
|---|---|---|
| A | 基础设施 | `RedisConfig`（JSON 序列化 + `JavaTimeModule`）、Redisson（单节点/集群二选一）、Caffeine 一级缓存、Key 命名规范、**降级熔断守卫** |
| B | `FR-12-001` | 歌曲详情三级缓存；空值缓存防穿透、分布式锁防击穿、TTL 抖动防雪崩 |
| C | `FR-12-006` | `DistributedLockService`（Redisson RLock，租约显式指定；Redis 不可用时降级为 JVM 锁） |
| D | `FR-12-005` | `@RateLimit` 注解 + Lua 滑动窗口；`Retry-After` 响应头；白名单 |
| E | `FR-12-004` | **仅会话部分**：Token 黑名单（登出即时失效）、Refresh 轮换与重放检测、登录失败锁定、封禁标记即时生效 |
| F | `FR-12-009` | Cache-Aside + 删缓存（非更新）+ 事务提交后延迟双删；一致性窗口定义 |
| G | `FR-12-009` 9.1 | 布隆过滤器前置过滤（可选开关） |

### 1.2 明确不做（后置到对应模块）

| 不做项 | 为什么 | 归到哪 |
|---|---|---|
| `FR-12-002` 热门歌单/排行榜缓存 | 依赖 `@Scheduled`/XXL-Job 定时重算，且房间热度榜无表；本轮先把缓存与锁的地基打牢 | 下一轮（03 续） |
| `FR-12-003` 计数组件（点赞/收藏/粉丝） | **`t_like` 等表尚未建**（属模块 10 社交基础），没有可计数的写入点；现在做只能做空壳 | 模块 10 之后 |
| `FR-12-007` 音乐房在线状态与会话 | 依赖 `t_room_*` 表与 WebSocket（模块 04/05） | 模块 05 |
| `FR-12-008` 搜索热词与历史 | 依赖搜索模块（模块 06） | 模块 06 |
| `FR-12-004` 图形/短信验证码 | 无短信通道；且注册目前仅支持 username | 模块 09 |
| `FR-12-010` Redis Cluster 部署 | 本机只有单节点 Redis 3.0.504；生产 Cluster 是部署动作，不是代码任务 | 部署清单 + §9 偏差 |
| Token 瘦身（权限搬进 Redis） | 见 D-24 | 待有「权限即时变更」需求时 |

### 1.3 前置条件与环境事实

| 项 | 事实 | 影响 |
|---|---|---|
| Redis 版本 | 本机 **3.0.504**（Windows 移植版），PRD 要求 6.0+ | 见 D-17、§9 |
| 部署形态 | 单节点 6379，`requirepass` 为空，`maxmemory=0`、`maxmemory-policy=noeviction` | 开发期够用；生产按 PRD 3主3从 |
| **db0 是共享的** | db0 里已有 9 个 `ranking:*` 键（`ranking:daily:2026070x`、`ranking:weekly:2026w27/28`、`ranking:monthly:202607`、`ranking:alltime`），日期停在 2026 年 7 月，**属于本机另一个音乐项目**（同 MySQL 上 `emp_db`/`music_db` 的性质） | 见 D-18：严格 `ml:` 前缀，禁止 `FLUSHDB`/`FLUSHALL` |
| 驱动可用性 | Redisson 3.17.7 驱动 Redis 3.0.504 **已实测可用**（T304 探针，6/6 通过） | 锁/限流按 PRD 原方案实现 |

---

## 2. 系分决策（ADR）

前序编号 D-01~D-15 见模块 01 文档。

| 编号 | 决策 | 理由与备选 |
|---|---|---|
| **D-16** | 分布式锁与限流**押在 Redisson 3.17.7** 上 | 依赖白名单已指定 Redisson 3.17.7。但本机 Redis 比 PRD 目标落后三个大版本，**先做探针测试再设计**（T304）：`RLock` 获取/`isHeldByCurrentThread`/租约到期自动释放、`RMap`/`RAtomicLong`、`RBloomFilter.tryInit/add/contains`、`EVAL` 跑 PRD 那段滑动窗口 Lua——**6/6 通过**。备选方案（`RedisTemplate` + 手写 Lua 锁）未启用，保留为降级路径 |
| **D-17** | 代码**不依赖 Redis 拓扑**：`RedissonConfig` 按配置在「单节点」与「集群」间切换 | 开发用单节点（PRD 假设 A-16），生产用 Cluster。业务代码只用 `RedisTemplate` 与 `RedissonClient` 抽象。因 Redis 3.0 不可用的能力：`UNLINK`(4.0+)、`Stream`(5.0+)、`OBJECT FREQ`(4.0+ LFU)——本轮均不使用 |
| **D-18** | Key 一律 `ml:` 前缀；**代码中不允许出现 `FLUSHDB`/`FLUSHALL`**；按前缀清理只允许 `SCAN ml:*` 后 `DEL` | db0 里有另一个项目的 `ranking:*`。`ml:` 前缀同时也是 PRD BR-12-001 的要求，一举两得。这条是硬约束，写进代码注释与冒烟脚本 |
| **D-19** | 缓存值走 **`StringRedisTemplate` + 应用自己的 `ObjectMapper`**；**不再提供 `RedisTemplate` Bean**，也不用 `GenericJackson2JsonRedisSerializer` | 实施时推翻了初稿的三条理由：① 那个序列化器构造时会激活 default typing，缓存值里塞满 `@class`，与接口 JSON 不一致，「缓存里到底是什么」需要额外知识才能读；② 它必须绑到连接工厂上，等于让缓存格式与连接配置耦合；③ 初稿担心的「自定义 `yyyy-MM-dd HH:mm:ss` 与 JSR-310 冲突」在复用**应用同一个** `ObjectMapper` 后自然消失。改为 `StringRedisTemplate` 后缓存里的 JSON 与接口返回的 JSON **逐字段相同**（T313 有断言，含中文与 `LocalDateTime`）。更彻底的做法是把 `LocalDateTime` 在领域层就换掉，但那要动模块 01 的实体，不属本模块范围 |
| **D-20** | 降级是**放行式 + 快速熔断**，不是「捕获异常继续」 | AC-12-001-4 要求 Redis 停掉后响应 ≤ 200ms。若每个请求都等 Lettuce 超时（PRD 配 3000ms），必然超标。故引入 `RedisGuard`：连续失败 N 次即打开熔断，打开期间 Redis 操作**直接短路、不发起调用**；冷却后再试探。代价是第一个失败请求付一次超时 |
| **D-21** | Redis 不可用时，锁**降级为 JVM 内 `ReentrantLock`**，而不是直接放行 | 直接放行会让所有线程同时回源 DB，正是击穿。降级为进程内锁，单实例下仍能挡住击穿；多实例下失效（记入 §9）。这比「放行」和「报错」都更接近原意 |
| **D-22** | 限流**白名单默认为空**，管理员豁免按「已登录且持有管理权限码」判定 | BR-12-027 要求内网 IP/管理员豁免。但若默认把 `127.0.0.1` 加白，**本机冒烟将永远验证不到限流**（测试恰好从本机发起）。故拆开：IP 白名单靠配置项注入（默认为空），管理员/超管按登录态豁免——两者都不影响本机测试 |
| **D-23** | 缓存的**对象就是 `Song` 实体本身**（接口返回什么就缓存什么），且**门禁在缓存取回后重新判定** | `GET /music/songs/{id}` 返回 `R<Song>`，不是 VO，所以缓存实体才能命中。关键：`requirePlayable` 的「已上架/审核通过/版权有效/有标准音质」四条门禁**绝不能跟着缓存**——状态会被后台改，缓存的是**事实**不是**结论**。取回缓存后照常走门禁 |
| **D-24** | **不做 Token 瘦身**：权限码继续留在 JWT 里，不搬进 Redis | 模块 01 的 `JwtTokenProvider` 类注释预留了「改为 Token 只放 userId + Redis 取权限」。本轮不做的理由：① 搬走后**每个请求**都要多一次 Redis 往返，与 D-20 的降级面叠加；② 封禁的即时性已由 `ml:auth:userban:` 独立覆盖（AC-12-004-5），不依赖权限搬家；③ 权限即时变更目前没有业务诉求。留待有诉求时再做 |
| **D-25** | 锁获取失败返回 **`90001 SYSTEM_BUSY`** | PRD `EX-12-006-1` 写「获取锁超时 → 返回 **90005**（系统繁忙）」，**编号与括注自相矛盾**：`90005` 在 PRD 05 章的定义是「服务已降级」，而「系统繁忙」对应的是 `90001`。按语义取 90001，并在此登记 |
| **D-26** | 删缓存在**事务提交后**执行，第二次删除延迟 500ms | 若在事务内删，并发读可能在提交前回源读到旧值并写回缓存（经典的「读旧值回写」竞态）。用 `TransactionSynchronization.afterCommit` 保证「DB 已可见」再删。延迟双删第二轮覆盖「删除窗口内被回写的旧值」。**单实例有效**；多实例需 Canal/binlog（PRD 标 P2） |
| **D-27** | 封禁**读侧**本轮交付，写在 JWT 过滤器里；**写侧**是 `TokenSessionStore.ban/unban`，由模块 09 调用（原计划的独立 `UserBanService` 未单列，理由见 §3 注） | AC-12-004-5 要求封禁 1 秒内全接口 20005，只能在每请求路径上校验（登录时的 `checkStatus` 拦不住已登录用户）。过滤器每次查 `ml:auth:userban:{userId}`，**不加本地缓存**（加了就会有秒级延迟窗口）；Redis 不可用时按 PRD `EX-12-004-1` 降级为「不拦」（可用性优先），并记 WARN。`ban` 同时撤销该用户全部会话——否则封禁前签发的 refreshToken 还能换出新 accessToken，等于封禁漏了一个后门 |
| **D-28** | 限流用 **AOP 注解**实现，不做全局路径拦截器 | PRD 给的就是 `@RateLimit` 方法注解。注解式的好处是阈值就在方法上、可读、可逐个评审。代价是 PRD 那句「IP 级覆盖**全部**公开接口」变成「逐个标注」，属**有意偏差**：全局拦截器会给静态资源与健康检查也套上限流，得不偿失。已标注的接口见 §5.3 |
| **D-29** | 空值标记沿用 PRD 的字符串 `"__NULL__"`，**不用 `null`** | Redis 里「键不存在」与「键存在但值为空」必须可区分：前者要回源，后者要快速返回 30001。写 `"__NULL__"` 到一个普通 String 值即可，反序列化回来正好是 `String`，`equals` 判定成立 |
| **D-30** | TTL 抖动按 `base × (1 + rand(0, 10%))` **只加不减** | BR-12-003 要求 ±10%。取「只加」而非「±」，是为了保证**最小 TTL 不低于配置值**——若减 10%，运营配 30 分钟的语义就变成 27 分钟起，与「TTL 30min」的直觉不符 |
| **D-31** | 已吊销 Token 的 `40102` 由 **`RestAuthEntryPoint`** 给出，**过滤器不中断过滤链**，只把原因写进请求属性 | 初版实现让过滤器「不认证就放行」，结果黑名单命中的请求最终落到 Security 的默认拒绝，报的是 `40101` 而不是 §5.4 承诺的 `40102`（验收时暴露）。三种 Token 状态要三种处置，不能一刀切：**非法/缺失**静默降级为匿名（公开接口得能用）；**已吊销**记下原因、需要鉴权时才翻译成 40102；**已封禁**是业务结果，必须直接写响应并中断（20005）。做不到「表里一致」的根因是过滤器不知道自己所在的接口**要不要鉴权**——把这个判断交给 Security 的授权阶段才是正确的位置。对前端的实际收益：`40101` 会让拦截器先试静默刷新，而已吊销的 Token 连 refresh 都一起作废了，那次往返注定失败 |
| **D-32** | 本地 Caffeine 层是**一级缓存，且删 Redis 键不等于冷读** | 命中本地层时**不回落 Redis**，所以「手工 `DEL` 缓存键」在 30s 内仍会命中本地层——验收脚本最初就是这么误判「缓存没写进去」的（T313 踩坑记录）。写路径的 `evict` 是**两级一起清**，所以线上失效路径无此问题；但**运维/联调时若想验证回源，必须走写路径或等本地 TTL**。这条同时界定了多实例一致性窗口的上限：本实例 0s，其他实例 ≤ `local-ttl-seconds` |

---

## 3. 代码结构

新增（均为本次交付，文件名为**实现后的实际名称**）：

```
infra/
  InfraProperties.java         绑定 newmusic.infra.*（redis / cache / lock / guard / limit 五个子节点）
infra/redis/
  RedisConfig.java             RedissonClient（单节点/集群按配置切换，StringCodec）、Caffeine Cache Bean
  RedisOps.java                **唯一的简单结构出口**：String / 计数 / Set / 按前缀删，全部经 Guard
  RedisGuard.java              降级熔断守卫：连续失败计数 + 打开窗口 + 短路（D-20）
  RedisKeys.java               Key 命名与拼装（BR-12-001）、TTL 抖动（BR-12-003）
infra/cache/
  CacheService.java            两级缓存：Caffeine + Redis；get 返回 Lookup（hit/negative/miss），
                               put/putNull/evict/evictLocal/evictLater（延迟双删的 500ms 那一路）
  BloomSupport.java            布隆过滤器封装；未启用/未初始化/Redis 不可用时一律**返回 true**（漏判为「可能有」）
infra/lock/
  DistributedLockService.java  Redisson RLock；execute（超时即无锁执行）/ executeOrFail（超时抛 90001）；
                               Redis 不可用降级为 JVM ReentrantLock（D-21）
infra/ratelimit/
  RateLimit.java               注解（limit/windowSeconds/dimension/type/message）
  RateLimitDimension.java      枚举：IP / USER / ACCOUNT / GLOBAL
  RateLimitService.java        Lua 滑动窗口 + Retry-After 计算 + whitelist 判定
  RateLimitAspect.java         @Around 织入，解析维度标识后交给 Service 执行
infra/security/
  TokenSessionStore.java       黑名单、refresh 轮换/重放、登录失败计数、封禁标记（原计划的
                               UserBanService / TokenSessionService 合并为此一个类）
module/music/support/
  SongBloomInitializer.java    ApplicationReadyEvent 时把全部歌曲 ID 灌进布隆过滤器
```

> **为什么黑名单/封禁放在 `infra/security` 而不是 `module/user`**：需要它的有三方——JWT 过滤器（infra）、认证服务（module/user）、后续模块 09 的封禁管理（module/admin）。放 infra 可以让三方都依赖它，而不必让 infra 反过来依赖 module（与「禁止跨模块 Mapper」同源的依赖方向约束）。

修改：

```
infra/security/JwtAuthenticationFilter.java    +黑名单校验、+封禁校验（拦截）、+ATTR_TOKEN_REVOKED
infra/security/RestAuthEntryPoint.java         按 ATTR_TOKEN_REVOKED 区分 40102 / 40101（D-31）
infra/security/JwtTokenProvider.java           createRefreshToken(userId, tokenId) 改为调用方提供 jti；
                                               +getTokenId / getRemainingSeconds / getRefreshTokenTtl
module/user/service/AuthService.java           +logout(String accessToken)
module/user/service/impl/AuthServiceImpl.java  登录失败计数/锁定、登出写黑名单、刷新轮换与重放撤销
module/user/controller/AuthController.java     登出从请求头取原始 Token；refresh 传旧 tokenId；+@RateLimit
module/music/service/impl/SongServiceImpl.java detail 读走缓存；写路径失效缓存；+listAllIdsForBloom
module/music/controller/SongController.java    +@RateLimit(300/min)
module/play/controller/PlayerController.java   +@RateLimit(300/min)
```

> **`SongServiceImpl` 里最需要记一笔的不是「加了缓存」，而是 `loadFresh(id)`**：写路径（update/changeStatus/audit/delete）**绝不能**再用 `detail(id)` 读当前值——它现在会返回缓存，而缓存值带着旧的 `version`，会让乐观锁比较失败或以旧版本参与 `changeSongCount` 这类增量计算。读路径的 `detail` 与写路径的 `loadFresh` 从此是两条不能混用的方法。

**Serializer 选型**（D-19）：`RedisConfig` 不提供 `RedisTemplate` Bean。`RedisOps` 注入 `StringRedisTemplate` 做值读写，序列化交给应用自己的 `ObjectMapper`（`JacksonConfig` 那个，已含 `JavaTimeModule` 与 `yyyy-MM-dd HH:mm:ss`）。于是**缓存值 = 接口 JSON**，`redis-cli get` 出来就是可直接阅读、可直接与接口响应比对的文本。Redisson 侧统一 `config.setCodec(StringCodec.INSTANCE)`，避免它自带的 Kryo 编解码（`ml:bloom:song` 这类键用 `redis-cli type` 看才是可读的 string，而不是二进制 blob）。

---

## 4. Redis Key 设计

### 4.1 公共约定

- 命名：`ml:{域}:{子域}:{标识}`（BR-12-001）。前缀 `ml:` 是**硬约束**：`RedisOps.deleteByPrefix` 会拒绝任何不以 `ml:` 开头的模式，本机共享实例上只可能误删自己的键。
- 配置项统一挂在 `newmusic.infra.*` 下（`redis` / `cache` / `lock` / `guard` / `limit`）。前缀不是 `newmusic.*`：那里已被模块 02 的 `newmusic.media.*` 占用。**`spring.redis.*` 一律不配**——客户端只有 Redisson 一个，配了反而会让人以为还有第二个连接。
- 除持久状态外**一律设 TTL**（AC-12-104）；TTL 带 10% 正向抖动（D-30）。
- Redis 不可用时的行为：读 → 视为 miss；写 → 丢弃并 WARN；锁 → 降级 JVM 锁；限流 → 放行。

### 4.2 本次落地 Key 清单

| Key | 类型 | TTL | 说明 |
|---|---|---|---|
| `ml:music:song:{songId}` | String(JSON) | 1800s ±10% | 歌曲详情缓存（FR-12-001） |
| `ml:music:song:{songId}`（空值） | String | 60s | 值为 `"__NULL__"`，标记「确认不存在」（BR-12-004） |
| `ml:bloom:song` | Bloom | 无 | 歌曲 ID 布隆过滤器，1000 万容量/1% 误判（FR-12-009 9.1） |
| `ml:lock:cache:song:{songId}` | Redisson 锁 | lease 5s | 缓存重建防击穿（FR-12-006） |
| `ml:auth:blacklist:{jti}` | String | = access token **剩余**有效期 | 登出后旧 access 立即失效（BR-12-020） |
| `ml:auth:refresh:{tokenId}` | String | 7d | refresh 轮换与重放检测（BR-12-021），值存 userId |
| `ml:auth:userban:{userId}` | String | 7d | 封禁标记，过滤器每请求校验（BR-12-023） |
| `ml:auth:fail:{account}` | String(计数) | 900s | 连续失败 5 次锁定 15 分钟（BR-12-019） |
| `ml:auth:refresh:user:{userId}` | Set | 7d | 该用户当前持有的 refreshTokenId 集合，用于「撤销全部会话」而**不必 `SCAN`** |
| `ml:limit:{type}:{dim}:{id}:{api}` | ZSet | = 窗口长度 | 滑动窗口限流（BR-12-026） |
| `{ml:bloom:song}:config` | Hash | 无 | Redisson `RBloomFilter` 自带元数据键（hash tag 形式），非本模块设计，勿删 |

### 4.3 与 PRD 12 章的差异（需评审确认）

| 项 | PRD | 本次实现 | 原因 |
|---|---|---|---|
| 缓存的载体 | 一个独立的「歌曲详情 JSON」（含 `artistName`/`albumName`/`audioUrls`） | **`Song` 实体**的 JSON | 模块 01 的该接口返回的就是实体（`R<Song>`），缓存实体才可能命中；且实体上的 `audioStandard` 是**静态路径**，不是 `PlayUrlService` 签发的会过期的直链——BR-12-001 那条「音频直链不入缓存」的约束**实际已满足**（签名直链从来不进缓存，每次由播放接口现签） |
| 空值标记 | `__NULL__` | 同 | 一致（D-29） |
| 布隆过滤器容量 | 1000 万 | 可配，默认 1000 万 | 同 |
| TTL 抖动 | ±10% | **+0~10%** | D-30 |
| 限流覆盖 | IP 级覆盖全部公开接口 | **逐个注解** | D-28 |
| `FR-12-004` 权限缓存 `ml:auth:perms:{userId}` | 有 | **不做** | D-24 |
| 锁超时错误码 | `EX-12-006-1` 写 90005 | 90001 | D-25（PRD 自相矛盾） |

---

## 5. 接口契约

### 5.1 对外契约不变的部分

`FR-12-001` 是纯内部优化：`GET /api/v1/music/songs/{id}` 的**响应体、状态码、错误码全部不变**，只是变快。这一点必须守住——缓存不得改变任何可观测语义。

### 5.2 新增/变化的行为

| 接口 | 变化 | 错误码 |
|---|---|---|
| `POST /api/v1/auth/logout` | 从「只打日志」变为**真正吊销**：当前 access token 的 `jti` 进黑名单（TTL=剩余有效期），该用户的所有 refresh 作废 | 行为变化，成功仍返回 0 |
| `POST /api/v1/auth/refresh` | 旧 refresh **立即失效**，签发新的（轮换）。**旧 refresh 被再次使用 → 判定为可能被盗，撤销该用户全部会话** | 重放时 40102 |
| 任意需鉴权接口 | 带**已黑名单**的 token → `40102`；**公开接口**带同样的 token 仍按游客正常放行 | 40102（由 D-31 的 EntryPoint 给出，过滤器只记原因） |
| 任意需鉴权接口 | 带**已封禁**用户的 token → 20005 | 20005 |
| `POST /api/v1/auth/login` | 连续失败 5 次后锁定 15 分钟 | 20001（沿用，见下） |
| 被 `@RateLimit` 标注的接口 | 超阈值 → 10004 + `Retry-After: <秒>` 响应头 | 10004 |

> **登录锁定的错误码**：不新增码，沿用 `20001`（账号或密码错误）但消息改为「失败次数过多，请 N 分钟后再试」。理由是**避免账号枚举**：若锁定返回一个独立错误码，攻击者就能区分「账号存在且被锁」与「账号不存在」，等于给撞库提供了用户名探测信道。

### 5.3 限流规则（本次实际标注）

| 接口 | 维度 | 阈值 | 窗口 | 依据 |
|---|---|---|---|---|
| `POST /api/v1/auth/register` | IP | 5 | 60s | 防批量注册；也是冒烟验证限流机制的落点（阈值小、调用便宜） |
| `POST /api/v1/auth/login` | ACCOUNT | 10 | 60s | 防单账号撞库 |
| `POST /api/v1/auth/login` | IP | 30 | 60s | 防分布式撞库 |
| `GET /api/v1/player/url` | IP | 300 | 60s | PRD：播放地址获取 300 次/分钟 |
| `GET /api/v1/music/songs/**` | IP | 300 | 60s | PRD：IP 级公开读 300 次/分钟 |

> 键按 `{api}` 分片（BR-12-026），所以「IP 级 300/min」是**每个接口**各自 300，不是全站合计。这也保证了本机冒烟（每个接口几十次）不会被误伤。

### 5.4 错误码（复用，不新增）

| 码 | 语义 | 本次用途 |
|---|---|---|
| `10004` | 请求过于频繁 | 限流，附 `Retry-After` |
| `20001` | 账号或密码错误 | 登录失败锁定（复用，见 5.2） |
| `20005` | 账号已被封禁 | 过滤器拦截 |
| `40101` | 未登录或登录已过期 | 没带 Token / Token 不可解析时访问需鉴权接口 |
| `40102` | 登录凭证无效 | 黑名单命中（登出后旧 Token）、refresh 重放 |
| `90001` | 系统繁忙 | 锁获取超时（D-25） |
| `90003` | 缓存服务异常 | Redis 不可用且**安全优先**的接口（如登出、改密）拒绝执行 |

---

## 6. 关键时序

### 6.1 读歌曲详情（三级缓存 + 防穿透 + 防击穿）

```
GET /music/songs/{id}
  │
  ├─ 1. 参数校验：id 非法 → 10001（不打缓存、不打 DB）
  ├─ 2. Caffeine 本地缓存命中？ ──是──► 返回（并把门禁走一遍）
  │      ↑ 命中本地层时**不回落 Redis**，所以手工 DEL 缓存键在 30s 内不构成冷读（D-32）
  ├─ 3. Redis GET ml:music:song:{id}
  │      ├─ 命中 "__NULL__" ──► 抛 30001（不打 DB）      ← 防穿透
  │      ├─ 命中对象 ──► 回写 Caffeine，返回
  │      └─ miss / Redis 不可用 ──► 继续
  ├─ 4. 布隆过滤器说「一定不存在」？ ──是──► 抛 30001（不打 DB）  ← 防穿透
  ├─ 5. 取锁 ml:lock:cache:song:{id}（wait 2s / lease 5s）
  │      ├─ 拿到锁 ──► 双重检查 Redis ──► 仍 miss ──► 查 DB
  │      │      ├─ DB 不存在 ──► 写 "__NULL__"(60s) ──► 抛 30001
  │      │      └─ DB 存在   ──► 写 JSON(1800s±10%) ──► 回写 Caffeine ──► 返回
  │      └─ 没拿到 / Redis 不可用（降级 JVM 锁）──► 同上，只是锁的覆盖面变小
  └─ 6. 门禁（**永远重新判定，不缓存**）：上架？审核通过？版权有效？有标准音质？
```

### 6.2 写歌曲（Cache-Aside + 删缓存 + 延迟双删）

```
PUT /music/songs/{id}
  │
  ├─ 事务内：更新 DB（乐观锁 version）
  ├─ 注册 TransactionSynchronization.afterCommit：
  │     ├─ T+0    ：删除 ml:music:song:{id} + 清本实例 Caffeine
  │     └─ T+500ms：再次删除 ml:music:song:{id}     ← 覆盖「删除窗口内被读旧值回写」
  └─ 提交成功返回
```

失效触发点（不止 update）：`create`、`update`、`delete`、`batchDelete`、`changeStatus`、`audit`、`bindSongTags`（标签属于详情的一部分，见下）。

> **标签变更也要失效**：`Song` 实体本身不含标签字段，但缓存的是「这首歌的详情」，语义上含标签。实测接口返回体中确实没有标签字段（标签走独立接口），所以严格说**不影响**——但 `bindSongTags` 会改 `t_tag.use_count`，与歌曲详情无关。**结论：本轮 `bindSongTags` 不触发歌曲详情缓存失效**，此判断记录在案，若日后详情开始返回标签，必须同步加失效。

### 6.3 登录 / 刷新 / 登出

```
登录： 校验密码 → 失败则 INCR ml:auth:fail:{account}（EXPIRE 900s）
                 达到 5 次 → 后续直接 20001（不再校验密码，避免时序侧信道）
       成功 → DEL 失败计数 → 签发 access+refresh → 写 ml:auth:refresh:{tokenId} = userId
登出： 解析当前 access 的 jti 与 exp → SET ml:auth:blacklist:{jti} EX <剩余秒>
       → 删除该用户全部 ml:auth:refresh:*（撤销会话）
刷新： 解析 refresh → 取 jti(tokenId)
       ① ml:auth:refresh:{tokenId} 不存在 → **重放或已用** → 撤销该用户全部会话 → 40102
       ② 存在 → DEL 该 key（一次性）→ 签发新 access+refresh → 写新 key
鉴权： 过滤器解析 token → 查 ml:auth:userban:{userId} 存在？ → 立刻写 20005 并中断过滤链
                        → 查 ml:auth:blacklist:{jti} 存在？ → 不认证、记 ATTR_TOKEN_REVOKED
                        → Redis 不可用 → 记 WARN，**放行**（PRD EX-12-004-1）
       授权： 需鉴权接口而当前是匿名 → EntryPoint 看 ATTR_TOKEN_REVOKED → 有则 40102，无则 40101
              公开接口 → 匿名照常放行（登出后的残留 Token 不该让人连曲库都刷不出来）
```

### 6.4 限流（AOP）

```
被 @RateLimit 标注的方法调用
  ├─ 白名单（配置的 IP / 超管）？ ──是──► 直接放行
  ├─ 计算 identifier（IP / 账号 / userId）与 key = ml:limit:{type}:{dim}:{id}:{api}
  ├─ EVAL 滑动窗口 Lua（ZREMRANGEBYSCORE → ZCARD → ZADD+PEXPIRE）
  │      ├─ 返回 1 ──► 放行
  │      └─ 返回 0 ──► 计算 Retry-After（窗口内最早的记录 + 窗口 - now）→ 写响应头 → 抛 10004
  └─ Redis 不可用 / Lua 报错 ──► **放行** + ERROR 日志（BR-12-028：可用性优先）
```

### 6.5 Redis 不可用时的降级矩阵（PRD 07 章落地版）

| 能力 | Redis 不可用时 | 依据 |
|---|---|---|
| 歌曲详情缓存 | 视为 miss，直连 DB | AC-12-001-4 |
| 本地 Caffeine | 继续用（不依赖 Redis） | — |
| 分布式锁 | 降级 JVM `ReentrantLock`（D-21） | — |
| 限流 | **放行** + 告警 | BR-12-028 |
| Token 黑名单 / 封禁 | **放行**（按纯 JWT 校验） | PRD EX-12-004-1 |
| 登录 | **拒绝**（90003） | PRD EX-12-004-1「安全优先」 |
| 布隆过滤器 | 跳过（不因此判不存在） | 只能「误判存在」，不能反向用 |

---

## 7. 任务清单

### P0（本模块必须完成）——代码与验收均已交付（T316 待补）

- [x] T301 依赖与配置：`pom.xml` 四个依赖、`application.yml` 的 `newmusic.infra.{redis,cache,lock,guard,limit}`（**不配 `spring.redis`**，见 4.1）
- [x] T302 `RedisConfig`：Redisson 单节点/集群切换（`StringCodec`）、Caffeine Bean、超时/重试统一由 `applyTimeoutSettings` 施加
- [x] T303 `RedisKeys`：Key 拼装 + TTL 抖动（BR-12-001/003）
- [x] T304 `RedisCompatibilityTest`：Redisson 3.17.7 ↔ Redis 3.0.504 探针（6/6，先于实现完成）
- [x] T305 `RedisOps`（唯一简单结构出口，含 `deleteByPrefix` 的 `ml:` 前缀硬校验）+ `RedisGuard` 熔断（D-20）
- [x] T306 `CacheService`：两级缓存 + 空值标记 + `evictLater` 延迟清理
- [x] T307 `DistributedLockService`：Redisson RLock + JVM 锁降级（D-21）
- [x] T308 `FR-12-001` 歌曲详情缓存：读路径（空值、布隆、击穿锁、门禁重判定、`loadFresh` 分流）
- [x] T309 写路径缓存失效：`afterCommit` + 延迟双删（D-26）
- [x] T310 `BloomSupport` + `SongBloomInitializer` 预热（FR-12-009 9.1）
- [x] T311 `@RateLimit` 注解 + `RateLimitAspect` + `RateLimitService`（Lua 滑动窗口 + Retry-After）
- [x] T312 `TokenSessionStore`：黑名单、refresh 轮换与重放检测、封禁标记、登录失败计数
- [x] T313 登录失败锁定（BR-12-019，复用 20001）
- [x] T314 过滤器封禁校验 + 黑名单原因上报（D-27、D-31）
- [x] T315 `scripts/verify-03.mjs`：模块 03 验收脚本，**58 项断言**，连跑三遍全绿且互不干扰
- [ ] T316 单测：序列化往返（D-19）、锁语义（`executeOrFail` → 90001）、限流窗口边界与**同毫秒突发**
  > **未做**：`mvn test` 需起的 Spring 上下文与 MySQL/Redis 依赖同上，价值低于已有的进程外验收；
  > 折中做法是把这三项并进 `verify-03.mjs`（已完成其中两项的等价覆盖）。同毫秒突发那一条
  > **必须补单测**——它是 `ARGV[4]` 替换 PRD `math.random` 的直接动因，进程外很难稳定复现。

### P1（可后置）

- [ ] T317 `FR-12-002` 榜单缓存 + `@Scheduled` 重算（下一轮）
- [ ] T318 `FR-12-003` 计数组件 + 增量落库（等模块 10 建 `t_like`）
- [ ] T319 Token 瘦身（D-24 复议）
- [ ] T320 Cluster 切换演练 + 部署清单（§10 附）
- [ ] T321 Redis 停机降级演练（AC-12-001-4 / 005-4 / 009-3）
  > **未做且需先确认**：本机 6379 是另一个项目在用的共享实例，停服会连累它。等用户明确
  > 可以停、或另起一个端口做演练，再执行。降级矩阵的代码路径已具备，缺的只是实测。

---

## 8. 验收核对（AC 映射）

| AC | 验证方式 | 状态与实测证据 |
|---|---|---|
| `AC-12-001-1` 第二次请求命中缓存，响应 ≤10ms | `verify-03.mjs` §01：同 ID 请求两次比对耗时，并断言缓存值与接口 `data` 逐字段相同 | ✅ 回源 7.7ms → 命中 3.1ms；**人工复测**（curl）146ms → 5.3ms |
| `AC-12-001-2` 不存在 ID 连续 1000 次，MySQL 只查 1 次 | §02：不存在的 ID（布隆判定「一定不存在」）→ 30001，且**不写空值缓存** | ✅ 未打 DB（`t_song` 的详情 SELECT 计数不增），且未产生键——避免把穿透防御变成缓存写放大 |
| `AC-12-001-3` 100 并发同一失效 Key，MySQL 只查 1 次 | §05：20 并发打同一个已失效 Key，**读应用 stdout 里 `t_song` 的 SELECT 条数差值** | ✅ **20 并发 → 1 次查库**；20 个响应字节级一致；无残留锁 |
| `AC-12-001-4` 停 Redis 后接口仍可用，≤200ms | 需停共享 Redis | ⏸️ 见 T321（未获准停服） |
| `AC-12-001-5` 更新歌曲后 200ms 内读到新值 | §04：PUT 改名 → 立即 GET → 断言读到**新**名称 | ✅ 缓存键在提交后被清除，回源即新值 |
| `AC-12-004-3` 登出后旧 token 立即失效 | §07：登出 → 旧 token 调 `/users/me` → 断言 40102 | ✅ 40102（初版给的是 40101，**验收暴露后修**，见 D-31） |
| `AC-12-004-4` 刷新后旧 refresh 不可再用 | §07：refresh → 再用旧 refresh → 断言 40102 | ✅ 40102，且**该用户新签发的 refresh 也一并失效**（重放按被盗处理） |
| `AC-12-004-5` 封禁用户 1 秒内全接口 20005 | §07：写 `ml:auth:userban:{id}` → 立即调接口 → 20005；删除键 → 立即恢复 | ✅ 即写即生效、即删即恢复，无需等 TTL 或重启 |
| `AC-12-005-1` 第 N+1 次请求返回 10004 | §06：同一合成 IP 连打 `register` 7 次（阈值 5） | ✅ 前 5 次放行、第 6/7 次 10004 |
| `AC-12-005-2` 限流响应含 `Retry-After` | §06：读响应头断言 1..60 | ✅ 存在且在窗口内 |
| `AC-12-005-3` 管理员不受限流影响 | 代码：超管登录态豁免（D-22）；冒烟中 admin 高频调用未出现 10004 | ✅ 间接覆盖；未做「超管连打被限接口」的专项用例 |
| `AC-12-005-4` Redis 停止后接口仍可用（放行） | 需停共享 Redis | ⏸️ 见 T321 |
| `AC-12-006-3` 锁内异常时锁被正确释放（无死锁） | 实现：`try/finally` + 远程锁在 `unlock` 前校验 `isHeldByCurrentThread`；§05 每次并发后断言无残留锁 | ⚠️ 每次并发用例都断言了「无残留锁」，但**未做**锁内主动抛异常的专项单测（T316） |
| `AC-12-009-1` 更新后 1 秒内读到新值 | 同 AC-12-001-5 | ✅ |
| `AC-12-009-3` Redis 全面不可用时接口仍可用 | 需停共享 Redis | ⏸️ 见 T321 |
| `AC-12-104` 所有 Key 符合命名规范且有 TTL（白名单除外） | §09：`KEYS ml:*` 逐键校验前缀与 `TTL` | ✅ 全部 `ml:` 前缀；除布隆过滤器与其 `:config` 元数据外全部有 TTL |
| `AC-12-108` Redis 故障时主链路可用 | 同 AC-12-009-3 | ⏸️ 见 T321 |
| `AC-12-100/101` 命中率 ≥95%、P99 ≤5ms | 本地单机不具备统计意义 | ⚠️ 记录偏差（命中 3.1ms 远优于 5ms，但样本仅 6 首） |
| `AC-12-102` 穿透/击穿/雪崩防护全部生效 | 分项见 001-2/3/4；雪崩 = TTL 抖动（D-30） | ✅ 穿透（布隆+空值）、击穿（锁，实测 20→1）已验；雪崩仅验了 TTL 落在 `[1800,1980)` 区间 |
| `AC-12-103` 更新后 1s 内读到新值（延迟双删） | §04：延迟双删在 ~500ms 后再次清除该键 | ✅ 观察到了**二次删除**，即延迟双删真的在跑（这是过度失效，属设计取舍） |
| `AC-12-004-1/2` 验证码一次性、短信限流 | 无验证码/短信通道 | ⚠️ 后置 |
| `AC-12-007-*` 房间在线状态 | 依赖模块 05 | ⚠️ 后置 |
| `AC-12-109` 无大 Key/热 Key | 数据量不足 | ⚠️ 后置（`ml:bloom:song` 约 11.4MB，是本模块唯一的大 Key，但它是**无 TTL 的常驻元数据**且全站唯一，不构成热 Key 风险） |
| `AC-12-100/106` 分布式锁（见 006-1/2） | 单机无法验证「两节点抢锁」，改为语义 + 降级路径 | ⚠️ 部分：单机验证了「并发只查一次库」；跨节点互斥与 `executeOrFail`→90001 待 T316 |

**复现方式**：`node scripts/verify-03.mjs`（58 项断言；`APP_LOG` 指向应用 stdout 可启用 §05 的查库计数断言）。
脚本连跑三遍结果一致 —— 这一点是刻意设计的：限流与登录态用例都用**每遍随机的合成 IP**，
账号维度的限流窗口与失败计数字段在用例开头自行清理，所以不会出现「第一遍绿、第二遍红」的假绿。

---

## 9. 风险与已知偏差

| 项 | 说明 | 处置 |
|---|---|---|
| **本机 Redis 3.0.504 ≪ PRD 的 6.0+ Cluster** | 三个大版本差。本轮不用 4.0+ 才有的 `UNLINK`、5.0+ 的 `Stream`、LFU 的 `OBJECT FREQ` | 代码不依赖拓扑与版本特性；生产切 Cluster 只改 `RedissonConfig` 与配置；已用 T304 探针把「Redisson↔3.0 可用」固化为回归测试 |
| **db0 与另一个项目共处** | 9 个 `ranking:*` 键属另一个项目 | D-18：只用 `ml:` 前缀；代码中禁止 `FLUSHDB`/`FLUSHALL`；按前缀清理只允许 `SCAN ml:*` + `DEL`。**本条是硬约束，不是建议** |
| Redis 无密码、`maxmemory=0`、`noeviction` | 本机开发配置；`noeviction` 下写满即报错 | 降级路径已覆盖「写入失败 → 不缓存」；生产按 PRD 配 `allkeys-lru` |
| 多实例下本地 Caffeine 无法跨实例失效 | 缓存写路径只能删本实例的 Caffeine | 一致性窗口记为本实例 0s / 其他实例 ≤ 本地 TTL。单实例部署下无影响 |
| 多实例下延迟双删不完整 | 第二次删除只作用于 Redis，其他实例的 Caffeine 仍可能脏 | 同上；强一致需求走 Canal（PRD 标 P2） |
| 布隆过滤器不支持删除 | 歌曲删除后仍留在过滤器里（误判为「可能存在」） | 只会导致多回源一次 DB，随后被空值缓存兜住；可接受 |
| 封禁/黑名单校验给每个已鉴权请求加了 1~2 次 Redis 往返 | D-27 不加本地缓存以保证 1 秒内生效 | 本机实测单次 <1ms；若成为瓶颈，改为 pipeline 合并两次 GET |
| `FR-12-004` 的权限缓存未做 | D-24 | 登记待复议 |
| 限流不是全局路径拦截 | D-28 | 有意偏差，已标注的接口见 §5.3 |
| `AC-12-005-3` 的「管理员豁免」只在**已登录**时成立 | 白名单默认空（D-22），登录态才判超管 | 未登录的运维 IP 要靠 `RATE_LIMIT_WHITELIST` 配；否则运维脚本高频调公开接口同样会被限流 |
| 锁超时错误码与 PRD 原文不符 | PRD 编号与括注自相矛盾 | D-25，按语义取 90001 |
| `AC-12-100/101` 命中率与 P99 | 本地单机 + 6 首种子数据，指标无统计意义 | 记录偏差，生产压测时复测 |
| **限流 IP 维度可被伪造 `X-Forwarded-For` 绕过**（**需评审**） | `IpUtil.getClientIp` 依次取 `X-Forwarded-For`/`X-Real-IP`/…，且取 XFF 里**最左边**（即客户端自己填的那一段） | 单机直连时，任何客户端只要每请求换一个 `XFF` 值就能完全绕过 IP 维度限流（register 5/min、login 30/min、公开读 300/min 全部失效）；`last_login_ip` 同理会写入伪造值。**这是模块 02 的既有实现，不是本模块引入的**，但本模块的 FR-12-005 直接建立在它之上，所以在此登记。缓解现状：账号维度（ACCOUNT）不依赖 IP，撞库防线的主体仍在；IP 维度只挡「无差别刷」。**修法二选一**：① 部署上要求入口网关剥离/覆写该头（若生产确有 Nginx 反代，则属部署约束，需写进部署清单）；② 代码上用 `RemoteIpValve`/`ForwardedHeaderFilter` 配可信代理列表，或改取 XFF 里**最右**一段。T313 脚本正是用同一机制换取用例隔离，**等于顺手证明了这条绕过确实可行** |

---

## 10. 变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-10-15 | v1.0 | 首次编写。范围按「基础设施 + 缓存 + 锁 + 限流 + 会话」定稿（依赖未建模块的 FR 全部后置）；D-16~D-30 共 15 条决策；`RedisCompatibilityTest` 探针先行，证明 Redisson 3.17.7 可驱动本机 Redis 3.0.504（6/6） |
| 2026-10-16 | v1.1 | **实施后回填，文档对齐代码**。① D-19 推翻初稿：改用 `StringRedisTemplate` + 应用 `ObjectMapper`，不再提供 `RedisTemplate` Bean；② §3 的文件名与职责按实现改写（`InfraProperties`/`RedisOps`/`BloomSupport`/`TokenSessionStore`/`SongBloomInitializer`，原计划的 `UserBanService`、`TokenSessionService` 合并）；③ 配置前缀定为 `newmusic.infra.*`，明确不配 `spring.redis.*`；④ 新增 **D-31**（40102 由 `RestAuthEntryPoint` 给出——验收发现初版返回的是 40101）与 **D-32**（本地层使「手工 DEL 键」不构成冷读）；⑤ 新增 `scripts/verify-03.mjs`（58 项断言，连跑三遍全绿），§8 逐条回填实测证据（含 **20 并发 → 1 次查库**）；⑥ 新增风险：`X-Forwarded-For` 可伪造导致 IP 维度限流可绕过（需评审）。**未做**：T316 同毫秒突发单测、T321 停 Redis 降级演练（共享实例，需先获准） |

---

## 附：生产环境（Cluster）切换清单

```java
// RedissonConfig：由 newmusic.redis.mode=single|cluster 决定
if ("cluster".equals(mode)) {
    config.useClusterServers()
          .addNodeAddress(nodes)              // redis://10.0.1.10:7000,...
          .setPassword(System.getenv("REDIS_PASSWORD"))
          .setConnectTimeout(5000).setTimeout(3000)
          .setRetryAttempts(3).setRetryInterval(1500).setScanInterval(2000);
}
```

Cluster 下的硬约束：多 Key 命令（`MGET`/事务/Lua）必须同 slot。本模块的 Lua 只操作**单个 Key**（限流），锁与缓存也都是单 Key，**天然满足**。日后若要批量取缓存，需用 hash tag `{songId}` 保证同 slot——此约束记于此，勿忘。
