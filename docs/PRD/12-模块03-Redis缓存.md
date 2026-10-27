# 12 · 模块 03：Redis 缓存与并发控制

[← 上一章：模块02 前端界面](./11-模块02-前端界面.md) | [返回目录](./README.md) | [下一章：模块04 WebSocket →](./13-模块04-WebSocket.md)

> **优先级**：P0
> **对应提示词条目**：3. Redis

---

## 1. 模块目标

用 Redis 支撑高并发读、计数、限流、分布式锁、会话与在线状态，把 MySQL 的 QPS 压力降到其容量的 20% 以内。

**衡量指标**：

| 指标 | 目标 |
|---|---|
| 热点接口缓存命中率 | ≥ 95% |
| 缓存读取 P99 | ≤ 5ms |
| Redis 命令平均耗时 | ≤ 1ms |
| 缓存不一致窗口 | ≤ 200ms（最终一致） |
| Redis 故障时主链路可用性 | 100%（降级直连 DB） |

---

## 2. 使用角色

系统内部能力，对用户透明。运维/超管可通过后台查看缓存状态与手动清理。

---

## 3. 前置条件

- Redis 6.0+（Cluster 模式），已按 [03 章](./03-系统总体架构.md) 部署。
- 已配置 Lettuce 连接池 + Redisson。

---

## 4. 技术实现方案

### 4.1 依赖与配置

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
</dependency>
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.17.7</version>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

```yaml
spring:
  redis:
    cluster:
      nodes: ${REDIS_NODES:127.0.0.1:7000,127.0.0.1:7001,127.0.0.1:7002}
      max-redirects: 3
    password: ${REDIS_PASSWORD:}
    timeout: 3000ms
    lettuce:
      pool:
        max-active: 200       # 最大连接数
        max-idle: 50
        min-idle: 20
        max-wait: 2000ms
      shutdown-timeout: 200ms
```

> **【假设 A-16】** 开发/测试环境使用单节点 Redis（简化），生产使用 Cluster。代码层使用 `RedisTemplate` 与 `RedissonClient` 抽象，**不依赖具体拓扑**。Cluster 相关限制（如 `mget` 需同 slot）须在编码时注意，必要时用 `hash tag` `{userId}` 保证同 slot。

### 4.2 RedisTemplate 配置（统一序列化）

```java
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // Key 用 String，Value 用 JSON（禁止 JDK 序列化：不可读、跨语言差、反序列化风险）
        StringRedisSerializer keySer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valSer = new GenericJackson2JsonRedisSerializer();

        template.setKeySerializer(keySer);
        template.setHashKeySerializer(keySer);
        template.setValueSerializer(valSer);
        template.setHashValueSerializer(valSer);
        template.afterPropertiesSet();
        return template;
    }

    /** 本地一级缓存（热 Key 兜底） */
    @Bean
    public Cache<String, Object> localCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(30))
                .recordStats()
                .build();
    }
}
```

> **强制项**：Value 序列化器**必须**是 JSON。JDK 序列化禁用的原因：① 不可读难排查；② 存在反序列化漏洞风险；③ 不支持跨语言；④ 类结构变更易失败。

---

## 5. 功能需求明细

### FR-12-001 歌曲详情缓存 【P0】

| 项 | 内容 |
|---|---|
| **功能目标** | 歌曲详情接口 QPS 降 95%，响应 ≤ 10ms |
| **使用角色** | 系统内部 |
| **前置条件** | 歌曲详情接口已实现 |

**操作流程**：

1. 请求 `GET /music/songs/{id}`。
2. 查本地 Caffeine（TTL 30s）→ 命中返回。
3. 查 Redis `ml:music:song:{id}`（TTL 30min）→ 命中回写本地缓存并返回。
4. 未命中 → 查 MySQL → 若存在则写 Redis + 本地缓存 → 返回。
5. 若 MySQL 也不存在 → 缓存空值 `__NULL__`（TTL 60s）→ 返回 `30001`。

**缓存数据结构**：

```json
// Key: ml:music:song:1857032849571328001   TTL: 30min
{
  "id": "1857032849571328001",
  "name": "晴天",
  "artistId": "1857000000000000001",
  "artistName": "周杰伦",
  "albumName": "叶惠美",
  "cover": "https://cdn.../cover.jpg",
  "duration": 269,
  "language": "国语",
  "genre": "流行",
  "copyrightStatus": 1,
  "audioUrls": { "standard": "...", "high": "...", "lossless": "..." },
  "playCount": 12345678,
  "cachedAt": 1760246400000
}
```

> **注意**：**音频直链不入缓存**（安全考虑与 URL 过期），只缓存元数据；播放地址由 `PlayUrlService` 单独签发。

**业务规则**：

- `BR-12-001`：缓存 Key 命名遵循 `ml:{域}:{子域}:{标识}`。
- `BR-12-002`：歌曲更新/删除时，**删除缓存**（非更新），下次读时重建（Cache-Aside）。
- `BR-12-003`：TTL 加入 ±10% 随机抖动，防同一时刻批量失效（雪崩）。
- `BR-12-004`：空值缓存标记为字符串 `"__NULL__"`，反序列化时特殊处理。
- `BR-12-005`：缓存对象**必须**带 `cachedAt` 时间戳，便于排查。

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-12-001-1` | Redis 连接失败 | 降级直连 MySQL，记 WARN，触发告警 |
| `EX-12-001-2` | 反序列化失败（结构变更） | 删除该 Key，回源 DB 重建 |
| `EX-12-001-3` | 缓存击穿（热 Key 失效瞬间） | 分布式锁 + 双重检查，仅一个请求回源 |
| `EX-12-001-4` | 缓存穿透（大量不存在的 ID） | 空值缓存 + 布隆过滤器 |
| `EX-12-001-5` | 缓存雪崩（大批 Key 同时失效） | TTL 随机化 + 多级缓存 + 熔断 |

**验收标准**：

- `AC-12-001-1`：第二次请求同一歌曲命中缓存（Redis hits 增长），响应 ≤ 10ms
- `AC-12-001-2`：查询不存在的 ID 连续 1000 次，MySQL 仅被查询 1 次
- `AC-12-001-3`：100 并发请求同一失效 Key，MySQL 仅被查询 1 次（防击穿）
- `AC-12-001-4`：停止 Redis，接口仍可用（降级 DB），响应 ≤ 200ms
- `AC-12-001-5`：更新歌曲后 200ms 内读到新值

---

### FR-12-002 热门歌单 / 排行榜缓存 【P0】

| 项 | 内容 |
|---|---|
| **功能目标** | 首页热门区块、排行榜秒开 |
| **使用角色** | 系统内部 |

**缓存结构**：

| 数据 | Key | 类型 | TTL | 刷新方式 |
|---|---|---|---|---|
| 热门歌单 | `ml:music:hot:playlists` | String(JSON数组) | 10min | 定时任务每 10min 重算 |
| 热门歌曲 | `ml:music:hot:songs` | ZSet | 10min | 定时重算（score=hot_score） |
| 飙升榜 | `ml:music:rank:soaring` | ZSet | 5min | 每小时重算 |
| 新歌榜 | `ml:music:rank:new` | ZSet | 5min | 每小时重算 |
| 原创榜 | `ml:music:rank:original` | ZSet | 5min | 每小时重算 |
| 歌手榜 | `ml:music:rank:artist` | ZSet | 30min | 每小时重算 |
| 房间热度榜 | `ml:room:rank:hot` | ZSet | 1min | 每分钟重算 |

**热度分计算公式（示例，可在系统配置中调整）**：

```
hot_score = play_count_weight × log10(play_count_24h + 1) × 10
          + like_weight      × log10(like_count_24h + 1) × 8
          + collect_weight   × log10(collect_count_24h + 1) × 6
          + comment_weight   × log10(comment_count_24h + 1) × 5
          + share_weight     × log10(share_count_24h + 1) × 7
          + completion_rate  × 20
          - skip_penalty     × skip_rate_24h × 15
          - time_decay       × hours_since_publish × 0.1

默认权重: play=1.0, like=1.0, collect=1.0, comment=1.0, share=1.0, skipPenalty=1.0
```

**业务规则**：

- `BR-12-006`：排行榜**读缓存，不实时计算**；由 XXL-Job 定时重算后覆盖。
- `BR-12-007`：榜单 Top N（默认 100）存入 Redis ZSet；查询 Top 20 用 `ZREVRANGE`。
- `BR-12-008`：榜单为空（首次部署）时，实时从 DB 计算一次并写入缓存。
- `BR-12-009`：榜单计算任务失败时，**保留旧缓存**（不删除），并告警。

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-12-002-1` | 定时任务失败 | 保留上次榜单，告警，下次重试 |
| `EX-12-002-2` | 榜单 Key 丢失 | 查询时降级为 DB 实时查询（限流保护） |
| `EX-12-002-3` | 首次部署无数据 | 全量计算并预热 |

**验收标准**：

- `AC-12-002-1`：排行榜接口响应 ≤ 20ms
- `AC-12-002-2`：榜单排序与 DB 计算结果一致
- `AC-12-002-3`：榜单任务失败时用户仍能看到上一次的榜单

---

### FR-12-003 计数组件（播放量/点赞/收藏/评论/粉丝）【P0】

| 项 | 内容 |
|---|---|
| **功能目标** | 高频计数不写库，保证 DB 稳定 |
| **使用角色** | 系统内部 |

**计数结构**：

| 计数 | Key | 类型 | Field | 落库策略 |
|---|---|---|---|---|
| 歌曲统计 | `ml:stat:song:{songId}` | Hash | play/like/collect/comment/share/download | 每 5min 或增量 ≥ 1000 落库 |
| 用户统计 | `ml:stat:user:{userId}` | Hash | fans/follow/play/feed/collect | 每 5min 落库 |
| 歌单统计 | `ml:stat:playlist:{id}` | Hash | play/collect/comment/share | 每 5min 落库 |
| 房间统计 | `ml:stat:room:{roomId}` | Hash | visit/gift/peak_online | 每 5min 落库 |

**操作流程（以点赞为例）**：

```
1. 用户点赞 → 写 MySQL t_like（唯一索引保证幂等）
2. 成功后 → HINCRBY ml:stat:song:{id} like 1
3. 返回成功（不等落库）
4. 定时任务每 5min：扫描 ml:stat:song:*，将增量写回 t_song
5. 落库成功后：HDEL 对应 field 或记录已同步基线
```

**业务规则**：

- `BR-12-010`：计数采用"**Redis 累加 + 定时落库**"，不做实时双写（避免写放大）。
- `BR-12-011`：落库采用**增量模式**：`UPDATE t_song SET like_count = like_count + #{delta}`，避免覆盖并发写。
- `BR-12-012`：计数展示**优先读 Redis**，Redis 未命中时读 DB。
- `BR-12-013`：取消点赞时 `HINCRBY ... -1`，计数**不允许小于 0**（取 max(0, value)）。
- `BR-12-014`：计数 Key **不设 TTL**（属于持久状态），但必须有落库兜底；若 Redis 数据丢失，以 DB 为准重建。
- `BR-12-015`：落库任务按 Hash Key 分片执行，避免单次任务过大。

**一致性保障**：

```
最终一致：Redis 计数（实时，可能有几秒延迟）→ DB（权威，5min 内追平）
重建：若 Redis 计数丢失 → 从 DB 重新加载（HINCRBY 到 DB 值 - Redis 当前值 的差值）
```

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-12-003-1` | Redis 丢失计数 | 从 DB 重建缓存 |
| `EX-12-003-2` | 落库任务失败 | 保留增量，下次任务累加（幂等基线） |
| `EX-12-003-3` | 计数为负 | 强制置 0 并告警 |
| `EX-12-003-4` | DB 与 Redis 差值 > 10% | 以 DB 为准，重置 Redis |

**验收标准**：

- `AC-12-003-1`：点赞接口响应 ≤ 50ms
- `AC-12-003-2`：点赞 1000 次后 Redis 计数 = DB 计数（5 分钟内追平）
- `AC-12-003-3`：取消点赞不会使计数为负
- `AC-12-003-4`：DB 计数在 5 分钟内追平 Redis（允许 ±1 误差）

---

### FR-12-004 验证码与登录会话 【P0】

| 项 | 内容 |
|---|---|
| **功能目标** | 安全、可撤销的会话管理 |
| **使用角色** | 全部用户 |

**Key 设计**：

| 用途 | Key | 类型 | TTL |
|---|---|---|---|
| 图形验证码 | `ml:auth:captcha:{uuid}` | String | 5min |
| 短信验证码 | `ml:auth:sms:{phone}` | String | 5min |
| 短信发送限流 | `ml:limit:sms:{phone}` | String(计数) | 1h |
| 登录会话 | `ml:auth:session:{userId}:{jti}` | String | 2h |
| Refresh Token | `ml:auth:refresh:{tokenId}` | Hash | 7d |
| Token 黑名单 | `ml:auth:blacklist:{jti}` | String | 剩余有效期 |
| 用户封禁标记 | `ml:auth:userban:{userId}` | String | 7d |
| 登录失败计数 | `ml:auth:fail:{account}` | String | 15min |
| 用户权限缓存 | `ml:auth:perms:{userId}` | Set | 30min |
| 登录设备列表 | `ml:auth:devices:{userId}` | ZSet | 7d |

**业务规则**：

- `BR-12-016`：图形验证码**一次性使用**（校验后立即删除）。
- `BR-12-017`：短信验证码校验成功后立即删除，防止重放。
- `BR-12-018`：短信发送限流：同一手机号 60s 内 1 条，1 小时内 ≤ 10 条，1 天内 ≤ 20 条。
- `BR-12-019`：登录失败计数：连续失败 5 次锁定 15 分钟（`ml:auth:fail:{account}`）。
- `BR-12-020`：登出时 access token 加入黑名单（TTL = 剩余有效期），refresh 删除。
- `BR-12-021`：刷新 token 时**轮换**：旧 refresh 删除，签发新 refresh（防重放）。若旧 refresh 被再次使用，说明可能被盗，**撤销该用户全部会话**。
- `BR-12-022`：多端登录：最多 5 个活跃 refresh，超出踢掉最早的（ZSet 按时间）。
- `BR-12-023`：封禁用户：写入 `ml:auth:userban:{userId}`，所有鉴权请求校验此 Key。

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-12-004-1` | Redis 不可用 | **登录功能降级为不可用**（安全优先），返回 `90003`；已登录用户 token 校验降级为纯 JWT 校验（不查黑名单） |
| `EX-12-004-2` | 验证码已过期 | `20002` |
| `EX-12-004-3` | Refresh Token 被重放 | 撤销该用户全部会话 + 安全告警 |
| `EX-12-004-4` | 第 6 个设备登录 | 踢掉最早的设备 |

**验收标准**：

- `AC-12-004-1`：图形验证码使用后立即失效
- `AC-12-004-2`：60 秒内重复发短信被拒绝
- `AC-12-004-3`：登出后旧 token 立即失效
- `AC-12-004-4`：刷新 token 后旧 refresh 不可再用
- `AC-12-004-5`：封禁用户 1 秒内所有接口返回 `20005`

---

### FR-12-005 接口限流 【P0】

| 项 | 内容 |
|---|---|
| **功能目标** | 防止接口被刷、保护后端 |
| **使用角色** | 系统内部 |

**限流维度与阈值**：

| 维度 | 接口示例 | 阈值 | 算法 |
|---|---|---|---|
| IP 级 | 全部公开接口 | 300 次/分钟 | 滑动窗口 |
| 用户级（写） | 发评论 | 10 次/分钟 | 滑动窗口 |
| 用户级（写） | 发动态 | 5 次/分钟 | 滑动窗口 |
| 用户级（写） | 发私信 | 30 次/分钟 | 滑动窗口 |
| 用户级（写） | 创建房间 | 3 次/小时 | 固定窗口 |
| 用户级（AI） | AI 搜索 | 20 次/小时 | 固定窗口 |
| 用户级（AI） | AI 歌单生成 | 10 次/天 | 固定窗口 |
| 用户级（读） | 播放地址获取 | 300 次/分钟 | 滑动窗口 |
| 房间级 | 房间发言 | 20 次/分钟 | 滑动窗口 |
| 全局 | 短信发送 | 10000 次/天 | 固定窗口 |

**实现方案（Redis + Lua 滑动窗口）**：

```lua
-- 滑动窗口限流 Lua 脚本（原子性）
-- KEYS[1] = 限流 Key, ARGV[1] = 窗口毫秒, ARGV[2] = 阈值, ARGV[3] = 当前毫秒
local key = KEYS[1]
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
local count = redis.call('ZCARD', key)
if count < limit then
    redis.call('ZADD', key, now, now .. '-' .. math.random(100000))
    redis.call('PEXPIRE', key, window)
    return 1
else
    return 0
end
```

**注解式使用**：

```java
@RateLimit(key = "comment:user", limit = 10, window = 60, dimension = Dimension.USER,
           message = "评论过于频繁，请稍后再试")
@PostMapping("/comments")
public R<CommentVO> create(@RequestBody @Valid CommentCreateReq req) { ... }
```

**业务规则**：

- `BR-12-024`：限流被触发返回 `10004`，HTTP 200，响应头带 `Retry-After`（剩余秒数）。
- `BR-12-025`：限流 Key 失效时间 = 窗口长度，自动清理。
- `BR-12-026`：限流**按维度**：`ml:limit:{type}:{dimension}:{identifier}:{api}`。
- `BR-12-027`：白名单：内网 IP、管理员账号、巡检账号豁免限流。
- `BR-12-028`：Redis 不可用时，限流**降级为放行**（可用性优先），但记录日志与告警（防 Redis 故障导致全站不可用）。
- `BR-12-029`：限流统计上报 Prometheus，用于观察限流触发频率。

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-12-005-1` | Redis 不可用 | 放行 + 告警（降级） |
| `EX-12-005-2` | Lua 脚本执行失败 | 放行 + ERROR 日志 |
| `EX-12-005-3` | 阈值配置错误 | 使用默认阈值 |

**验收标准**：

- `AC-12-005-1`：1 分钟内第 11 次发评论返回 `10004`
- `AC-12-005-2`：限流响应含 `Retry-After` 头
- `AC-12-005-3`：管理员账号不受限流影响
- `AC-12-005-4`：Redis 停止后接口仍可用（放行）

---

### FR-12-006 分布式锁 【P0】

| 项 | 内容 |
|---|---|
| **功能目标** | 跨节点互斥，保护临界区 |
| **使用角色** | 系统内部 |

**使用场景**：

| 场景 | 锁粒度 | 说明 |
|---|---|---|
| 缓存重建（防击穿） | `ml:lock:cache:song:{id}` | 只让一个请求回源 DB |
| 房间控播 | `ml:lock:room:play:{roomId}` | 同一房间控播串行 |
| 麦位分配 | `ml:lock:room:mic:{roomId}` | 防止两个用户抢同一麦位 |
| 排麦队列操作 | `ml:lock:room:queue:{roomId}` | 队列变更串行 |
| 用户注册（防重复） | `ml:lock:register:{phoneHash}` | 防并发注册同手机号 |
| 计数落库 | `ml:lock:stat:{table}` | 防止并发落库重复 |
| 订单/审批（预留） | `ml:lock:approve:{id}` | — |

**实现（Redisson RLock）**：

```java
@Service
public class DistributedLockService {

    @Autowired private RedissonClient redisson;

    /** 标准用法：tryLock + 看门狗自动续期 */
    public <T> T executeWithLock(String lockKey, long waitSeconds, long leaseSeconds,
                                 Supplier<T> action) {
        RLock lock = redisson.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAILED);
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.SYSTEM_BUSY);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

**业务规则**：

- `BR-12-030`：锁**必须**设置等待时间与持有时间，禁止无限等待。
- `BR-12-031`：释放锁**必须**判断 `isHeldByCurrentThread()`，防止误释放他人锁。
- `BR-12-032`：锁的粒度**尽可能小**（按业务主键），禁止大范围锁（如全表锁）。
- `BR-12-033`：锁内操作**必须**短平快（< 1s），禁止锁内做网络 IO 或耗时计算。
- `BR-12-034`：Redisson 看门狗自动续期仅适用于**未指定 leaseTime** 的场景；指定 leaseTime 则不续期（推荐显式指定，避免锁泄漏）。
- `BR-12-035`：分布式锁**不保证强一致**（Redis 主从切换可能丢锁），因此锁内操作**必须自身幂等**。

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-12-006-1` | 获取锁超时 | 返回 `90005`（系统繁忙），前端可重试 |
| `EX-12-006-2` | 持锁者宕机 | leaseTime 到期自动释放 |
| `EX-12-006-3` | Redis 主从切换丢锁 | 依赖业务幂等兜底 |
| `EX-12-006-4` | 锁内抛异常 | finally 释放锁 |

**验收标准**：

- `AC-12-006-1`：两个节点并发抢同一麦位，仅一个成功
- `AC-12-006-2`：持锁者宕机后锁在 leaseTime 内释放
- `AC-12-006-3`：锁内异常时锁被正确释放（无死锁）

---

### FR-12-007 音乐房在线状态与会话 【P0】

| 项 | 内容 |
|---|---|
| **功能目标** | 维护房间在线成员、WebSocket 会话映射 |
| **使用角色** | 系统内部 |

**Key 设计**：

| 用途 | Key | 类型 | TTL |
|---|---|---|---|
| 房间在线成员 | `ml:room:online:{roomId}` | Set（userId） | 无（心跳维护） |
| 房间在线数 | `ml:room:online_count:{roomId}` | String | 无 |
| 用户在线状态 | `ml:room:user:{userId}` | String（roomId） | 心跳 TTL 90s |
| 房间权威播放态 | `ml:room:play:{roomId}` | Hash | 房间生命周期 |
| 房间状态版本号 | `ml:room:ver:{roomId}` | String（INCR） | 房间生命周期 |
| 房间麦位 | `ml:room:mic:{roomId}` | Hash（pos→userId） | 房间生命周期 |
| 排麦队列 | `ml:room:mic:queue:{roomId}` | ZSet（userId→seq） | 房间生命周期 |
| 播放队列 | `ml:room:queue:{roomId}` | List（songId） | 房间生命周期 |
| 禁言列表 | `ml:room:mute:{roomId}` | Hash（userId→untilTs） | 房间生命周期 |
| 黑名单 | `ml:room:blacklist:{roomId}` | Set（userId） | 房间生命周期 |
| WS 会话映射 | `ml:ws:session:{userId}` | String（nodeId:sessionId） | 心跳 TTL |
| WS 节点负载 | `ml:ws:node:{nodeId}` | String（连接数） | TTL 60s |

**房间权威播放态结构**：

```json
// Key: ml:room:play:{roomId}   Hash
{
  "songId": "1857032849571328001",
  "songName": "晴天",
  "artistName": "周杰伦",
  "cover": "...",
  "duration": 269,
  "position": 45.2,          // 基准进度（秒）
  "serverTime": 1760246400000, // position 对应的服务端时间戳
  "isPlaying": true,
  "playMode": 1,
  "version": 128             // 状态版本号，每次控播递增
}
```

**业务规则**：

- `BR-12-036`：房间在线集合**由心跳维护**：成员每 30s 上报心跳，刷新 `ml:room:user:{userId}`（TTL 90s）；定时任务清理超过 90s 未心跳的用户。
- `BR-12-037`：房间在线数**以 Set 的 SCARD 为准**，但高频读取时用 `online_count` 缓存（Set 变更时同步更新）。
- `BR-12-038`：房间播放态**以 Redis 为权威源**，WebSocket 广播只做通知，不做状态存储。
- `BR-12-039`：房间状态每次变更 `INCR ml:room:ver:{roomId}`，客户端按 version 判断是否应用（防乱序）。
- `BR-12-040`：房间解散时**删除全部** `ml:room:*:{roomId}` 相关 Key。

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-12-007-1` | 心跳丢失 | 90s 后自动清理，广播离线 |
| `EX-12-007-2` | 在线数不一致（Set vs 缓存） | 定时任务每 1min 对账修正 |
| `EX-12-007-3` | Redis 重启丢房间状态 | 从 MySQL `t_room_member` 重建成员集合；播放态重置为暂停 |
| `EX-12-007-4` | 幽灵成员（已断连未清理） | 心跳超时清理 + 定时全量对账 |

**验收标准**：

- `AC-12-007-1`：用户异常断连 90s 后从在线列表移除
- `AC-12-007-2`：50 人房间在线数与实际一致
- `AC-12-007-3`：Redis 重启后房间成员可从 DB 恢复
- `AC-12-007-4`：房间解散后相关 Key 全部清除

---

### FR-12-008 搜索热词与历史 【P1】

| 项 | 内容 |
|---|---|
| **功能目标** | 热搜榜与个人搜索历史 |
| **使用角色** | 全部 |

**Key 设计**：

| 用途 | Key | 类型 | TTL |
|---|---|---|---|
| 实时热搜 | `ml:search:hot:realtime` | ZSet | 1h |
| 日热搜 | `ml:search:hot:{yyyyMMdd}` | ZSet | 7d |
| 用户搜索历史 | `ml:search:his:{userId}` | List（最多 20） | 30d |
| 搜索联想词 | `ml:search:suggest:{prefix}` | String(JSON) | 1h |

**业务规则**：

- `BR-12-041`：用户搜索时 `ZINCRBY ml:search:hot:realtime 1 {keyword}`。
- `BR-12-042`：搜索历史用 `LPUSH` + `LTRIM 0 19` 保留最近 20 条；重复搜索时先 `LREM` 再 `LPUSH`（去重置顶）。
- `BR-12-043`：热搜榜 Top 10 = `ZREVRANGE ml:search:hot:realtime 0 9`，**过滤敏感词与低质词**。
- `BR-12-044`：热搜榜每小时落库到 `t_search_hot` 用于趋势分析。
- `BR-12-045`：搜索历史仅本人可见，登出不影响（按 userId 存储）。

**验收标准**：

- `AC-12-008-1`：搜索历史最多 20 条，重复词置顶
- `AC-12-008-2`：热搜榜实时更新（1 分钟内）
- `AC-12-008-3`：热搜榜不含敏感词

---

### FR-12-009 缓存穿透 / 雪崩 / 一致性策略 【P0】

> 本章为**设计规范**，是前述各缓存需求的统一约束。

#### 9.1 缓存穿透（查询不存在的数据）

| 手段 | 说明 | 适用 |
|---|---|---|
| **空值缓存** | 查询为空时缓存 `__NULL__`，TTL 60s | 通用 |
| **布隆过滤器** | 启动时加载全部歌曲/用户 ID，查询前先判断 | 歌曲、用户等 ID 集合 |
| **参数校验** | ID 格式校验，拒绝明显非法的 ID | 通用 |
| **限流** | 对同一 IP 的高频不存在查询限流 | 通用 |

**布隆过滤器实现**：

```java
// 使用 Redisson 的 RBloomFilter
RBloomFilter<Long> filter = redisson.getBloomFilter("ml:bloom:song");
filter.tryInit(10_000_000L, 0.01);   // 预计 1000 万，误判率 1%

// 查询前判断
if (!filter.contains(songId)) {
    return null;   // 一定不存在，直接返回，不打 DB
}
```

> **注意**：布隆过滤器有**误判**（可能误判存在，不会误判不存在）。因此不能作为唯一依据，只作为前置过滤。新增数据时需 `filter.add(id)`；删除不支持（用重建或计数布隆）。

#### 9.2 缓存雪崩（大批 Key 同时失效）

| 手段 | 说明 |
|---|---|
| **TTL 随机化** | `ttl = baseTtl + random(0, baseTtl * 0.1)` |
| **多级缓存** | Caffeine（本地，TTL 短） + Redis（分布式，TTL 长） |
| **热点永不过期** | 逻辑过期：不设 TTL，值内带 `expireAt`，异步刷新 |
| **熔断降级** | 缓存不可用时熔断 DB 查询，返回兜底数据 |
| **预热** | 发布/重启后主动预热核心数据 |
| **集群高可用** | Redis Cluster + 多副本 |

**逻辑过期实现（热 Key 防击穿与雪崩）**：

```java
@Data
public class LogicalCache<T> {
    private T data;
    private long expireAt;    // 逻辑过期时间戳
}

// 读取
LogicalCache<Song> cache = get(key);
if (cache == null) return loadFromDbAndCache(key);
if (System.currentTimeMillis() < cache.getExpireAt()) {
    return cache.getData();                    // 未过期，直接返回
}
// 已逻辑过期：异步刷新，当前请求返回旧值（保证可用性）
asyncExecutor.execute(() -> rebuildAsync(key));
return cache.getData();
```

#### 9.3 缓存击穿（热 Key 失效瞬间高并发）

| 手段 | 说明 |
|---|---|
| **分布式锁 + 双重检查** | 只让一个请求回源，其他等待 |
| **逻辑过期** | 同上，永不让请求落空 |
| **本地缓存兜底** | Caffeine 短 TTL 顶住瞬间流量 |

```java
public Song getSong(Long id) {
    String key = "ml:music:song:" + id;
    // 1. 本地缓存
    Song local = (Song) localCache.getIfPresent(key);
    if (local != null) return local;
    // 2. Redis
    Song cached = (Song) redisTemplate.opsForValue().get(key);
    if (cached != null) { localCache.put(key, cached); return cached; }
    // 3. 分布式锁 + 双重检查
    return lockService.executeWithLock("ml:lock:cache:song:" + id, 2, 5, () -> {
        Song again = (Song) redisTemplate.opsForValue().get(key);
        if (again != null) return again;
        Song db = songMapper.selectById(id);
        if (db == null) {
            redisTemplate.opsForValue().set(key, NULL_MARKER, 60, TimeUnit.SECONDS);
            return null;
        }
        redisTemplate.opsForValue().set(key, db, randomTtl(1800), TimeUnit.SECONDS);
        localCache.put(key, db);
        return db;
    });
}
```

#### 9.4 缓存一致性（Redis vs MySQL）

**采用 Cache-Aside（旁路缓存）+ 删除而非更新**：

```
读：Cache → miss → DB → 写 Cache
写：更新 DB → 删除 Cache（不更新 Cache）
```

**为什么删除而不是更新 Cache**：

1. 并发写会导致 Cache 与 DB 不一致（两次更新的顺序不可控）。
2. 删除更简单，且下次读时重建，天然规避脏数据。
3. 删除是幂等的。

**延迟双删（应对"读旧值回写"竞态）**：

```
1. 删除 Cache
2. 更新 DB
3. 延迟 500ms（或 1s）后再次删除 Cache   ← 删除期间被读到的旧值重新写入的缓存
```

> **说明**：延迟双删能覆盖 99% 的场景，但不是强一致。对强一致要求高的场景（如库存、余额），**不依赖缓存**，直接读 DB 或加锁。

**进一步的一致性保障**：

| 手段 | 说明 | 场景 |
|---|---|---|
| **版本号** | 缓存值带 `version`，DB 更新时递增，读时比对 | 关键数据 |
| **订阅 Binlog（Canal）** | DB 变更通过 Canal 投递 MQ，消费者删除缓存 | 高一致性要求（P2） |
| **短 TTL 兜底** | 即使不一致也会在 TTL 内自动纠正 | 通用兜底 |

**一致性目标（明确不追求强一致）**：

- 普通数据（歌曲、歌单）：**最终一致，窗口 ≤ TTL**（30 分钟，但延迟双删后通常 < 1s）。
- 计数数据（播放量、点赞）：**最终一致，窗口 ≤ 5 分钟**（落库周期）。
- 权限数据：**最终一致，窗口 ≤ 60s**（`BR-02-014`）。
- 房间状态：**强一致**（Redis 为唯一权威源，不落 DB 实时态）。

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-12-009-1` | 删除缓存失败 | 重试 3 次 → 失败记 ERROR + 依赖 TTL |
| `EX-12-009-2` | 更新 DB 成功但进程崩溃前未删缓存 | 依赖 TTL 兜底；关键数据用 Canal |
| `EX-12-009-3` | 大量请求打穿到 DB | 熔断 + 限流 + 返回兜底数据 |

**验收标准**：

- `AC-12-009-1`：更新数据后 1 秒内读到新值
- `AC-12-009-2`：并发 1000 请求不存在的 ID，DB 查询 ≤ 10 次
- `AC-12-009-3`：Redis 全面不可用时接口仍可用（降级 DB）
- `AC-12-009-4`：模拟缓存一致性故障，60000 次请求后 DB 与缓存最终一致

---

### FR-12-010 Redis Cluster 部署与运维 【P0】

| 项 | 内容 |
|---|---|
| **功能目标** | 高可用、可扩展的 Redis 集群 |
| **使用角色** | 运维 |

**部署方案对比**：

| 方案 | 优点 | 缺点 | 建议 |
|---|---|---|---|
| 主从 + 哨兵（Sentinel） | 配置简单，客户端支持好 | 单节点容量上限；扩容需人工 | 小规模（< 32G） |
| **Cluster** | 水平扩展、自动分片、高可用 | 多 Key 操作受限于同 slot；运维复杂 | **推荐（生产）** |
| Proxy（Codis/Twemproxy） | 客户端无感 | 多一跳，运维复杂 | 不推荐 |

**生产配置（推荐 Cluster）**：

```
3 主 3 从，每主 4C8G，最大内存 8GB
maxmemory-policy: allkeys-lru
appendonly: yes (AOF 每秒刷盘)
save: 900 1 / 300 10 / 60 10000
cluster-node-timeout: 5000
```

**Cluster 使用约束**：

| 约束 | 说明 | 应对 |
|---|---|---|
| 多 Key 命令需同 slot | `MGET`、`MSET`、事务、Lua | 用 `hash tag`：`{userId}:xxx` 保证同 slot |
| 不支持多数据库 | 只有 db0 | 用 Key 前缀区分环境 |
| Redisson 配置 | 需 `clusterServersConfig` | 见下方配置 |

```java
@Configuration
public class RedissonConfig {
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useClusterServers()
              .addNodeAddress("redis://10.0.1.10:7000", "redis://10.0.1.11:7001", "redis://10.0.1.12:7002")
              .setPassword(System.getenv("REDIS_PASSWORD"))
              .setConnectTimeout(5000)
              .setTimeout(3000)
              .setRetryAttempts(3)
              .setRetryInterval(1500)
              .setScanInterval(2000);
        return Redisson.create(config);
    }
}
```

**容量规划**：

```
内存估算：
- 歌曲详情缓存：50 万首 × 2KB ≈ 1GB
- 用户会话：10 万 × 1KB ≈ 100MB
- 计数字段：50 万 Hash × 200B ≈ 100MB
- 房间状态：1000 房间 × 10KB ≈ 10MB
- 搜索历史：10 万 × 2KB ≈ 200MB
- 其他（限流/锁/热词）：≈ 500MB
合计 ≈ 2GB → 留 3 倍冗余 → 6GB，取 8GB 实例
```

**监控指标**：

| 指标 | 告警阈值 |
|---|---|
| 内存使用率 | > 80% |
| 命中率 | < 90% |
| 慢查询（> 10ms） | > 10 次/分钟 |
| 连接数 | > 80% maxclients |
| 主从延迟 | > 1s |
| 阻塞客户端数 | > 5 |
| 集群节点状态 | 非 ok |
| 大 Key（> 10KB） | 存在 |

**异常情况**：

| 编号 | 场景 | 处理 |
|---|---|---|
| `EX-12-010-1` | 主节点宕机 | 从节点自动提升，客户端重定向（秒级） |
| `EX-12-010-2` | 集群脑裂 | `cluster-node-timeout` 控制；业务侧容忍短暂不一致 |
| `EX-12-010-3` | 内存打满 | `allkeys-lru` 淘汰；告警并要求扩容 |
| `EX-12-010-4` | 大 Key 阻塞 | 拆分大 Key；`SCAN` 替代 `KEYS` |

**验收标准**：

- `AC-12-010-1`：杀掉 1 个主节点，服务在 10s 内自动恢复
- `AC-12-010-2`：集群内存使用率 < 80% 稳定运行
- `AC-12-010-3`：缓存命中率 ≥ 95%
- `AC-12-010-4`：无大 Key（> 10KB）与热 Key 单点

---

## 6. Key 全量清单

> 汇总见 [04 章第 13 节](./04-数据模型与数据库设计.md)。运维可通过 `SCAN ml:*` 审计。

---

## 7. 降级策略总表

| Redis 状态 | 影响 | 降级策略 |
|---|---|---|
| 完全不可用 | 全部缓存失效 | 读直连 DB + 熔断保护；登录不可用（安全优先）；限流放行；计数降级为直写 DB |
| 部分节点不可用 | 部分 Key 不可达 | Cluster 自动转移；客户端重试 |
| 内存满 | 写入失败 | LRU 淘汰；写入失败降级为不缓存 |
| 高延迟 | 接口变慢 | 熔断（Sentinel），走本地缓存 + DB |

---

## 8. 验收标准汇总

| 编号 | 验收标准 | 优先级 |
|---|---|---|
| `AC-12-100` | 热点接口缓存命中率 ≥ 95% | P0 |
| `AC-12-101` | 缓存 P99 ≤ 5ms | P0 |
| `AC-12-102` | 缓存穿透/击穿/雪崩防护全部生效 | P0 |
| `AC-12-103` | 数据更新后 1s 内读到新值（延迟双删生效） | P0 |
| `AC-12-104` | 所有 Key 符合命名规范且有 TTL（白名单除外） | P0 |
| `AC-12-105` | 限流按维度生效且可通过 `Retry-After` 获知重试时间 | P0 |
| `AC-12-106` | 分布式锁无死锁，锁内异常可正确释放 | P0 |
| `AC-12-107` | 房间在线状态与实际一致（误差 ≤ 1 人） | P0 |
| `AC-12-108` | Redis 集群故障时主链路可用 | P0 |
| `AC-12-109` | 无大 Key（> 10KB）、无热 Key 单点 | P1 |

---

[← 上一章：模块02 前端界面](./11-模块02-前端界面.md) | [返回目录](./README.md) | [下一章：模块04 WebSocket →](./13-模块04-WebSocket.md)
