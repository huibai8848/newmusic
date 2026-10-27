package com.example.newmusic.infra.redis;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 对 Redis 简单数据结构的唯一出口。
 *
 * <p><b>为什么所有操作都收在这一个类里。</b>两个原因：
 * <ol>
 *   <li>降级策略只需要在这里实现一次，不必在每个调用点重复 try-catch；</li>
 *   <li>值统一是 JSON 字符串（{@link StringRedisTemplate} + 应用自己的 ObjectMapper），
 *        缓存里存的就是接口返回的那份 JSON，{@code redis-cli GET} 直接可读。</li>
 * </ol>
 *
 * <p><b>刻意没有用 {@code GenericJackson2JsonRedisSerializer}。</b>系分 D-19 原本打算用它，
 * 它会在值里写入 {@code @class} 类型信息，于是 {@code LocalDateTime} 被序列化成
 * {@code ["java.time.LocalDateTime","..."]} 数组——既不可读、也不能跨语言消费。
 * 改用「应用自己的 ObjectMapper 序列化 + StringRedisTemplate 存字节」后，
 * 缓存与本项目接口的 JSON 形态**完全一致**（时间格式、雪花 ID 转字符串都一致），
 * 顺带消掉了 Redis 序列化器与连接工厂编解码器之间的耦合。
 *
 * <p>所有方法在 Redis 不可用时**不抛异常**：读返回空、写静默丢弃并记日志（系分 D-20）。
 */
@Slf4j
@Component
public class RedisOps {

    /** Lua 脚本走 Redisson（模块 03 的探针测试已证明它在 Redis 3.0.504 上可用） */
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final RedisGuard guard;

    public RedisOps(StringRedisTemplate redis, RedissonClient redisson, RedisGuard guard) {
        this.redis = redis;
        this.redisson = redisson;
        this.guard = guard;
    }

    /**
     * Redis 当前是否可用（熔断未打开）。
     *
     * <p>调用方需要它来区分两件**后果完全不同**的事：「键不存在」和「Redis 连不上」。
     * 例如 refreshToken 查不到，前者是重放攻击、应当撤销全部会话，后者只是缓存服务故障、
     * 绝不能因此把用户踢下线。仅靠 {@link #get} 返回的空 Optional 区分不了这两者。
     */
    public boolean isAvailable() {
        return guard.isAvailable();
    }

    public Optional<String> get(String key) {
        return guard.call("GET " + key, () -> Optional.ofNullable(redis.opsForValue().get(key)), Optional.empty());
    }

    public void set(String key, String value, Duration ttl) {
        guard.run("SET " + key, () -> {
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                redis.opsForValue().set(key, value);
            } else {
                redis.opsForValue().set(key, value, ttl);
            }
        });
    }

    /**
     * 删除单个键。
     *
     * @return true 表示「确实删掉了一个存在的键」。这个返回值有语义：
     *         refresh 轮换靠它判定「是不是有人抢先用过了」——两个并发请求都读到同一个
     *         refreshToken，只有其中一个的 delete 会返回 true，另一个拿到 false，
     *         就按重放处理（系分 §6.3）。
     */
    public boolean delete(String key) {
        return guard.call("DEL " + key, () -> Boolean.TRUE.equals(redis.delete(key)), false);
    }

    /**
     * 按前缀批量删除。
     *
     * <p>走 Redisson 的 {@code deleteByPattern}（探针测试已验证）而不是 {@code KEYS}——
     * {@code KEYS} 会阻塞整个 Redis，共享实例上更是不能碰。
     *
     * <p><b>前缀必须落在 {@code ml:} 下，否则直接拒绝。</b>本机 db0 里有另一个项目的
     * {@code ranking:*} 键，这是系分 D-18 定的硬约束，用它把误伤堵死在代码里。
     */
    public long deleteByPrefix(String prefix) {
        if (prefix == null || !prefix.startsWith(RedisKeys.PREFIX)) {
            throw new IllegalArgumentException("按前缀删除只允许操作 " + RedisKeys.PREFIX + " 下的键，收到：" + prefix);
        }
        return guard.call("DEL_BY_PREFIX " + prefix,
                () -> redisson.getKeys().deleteByPattern(prefix + "*"), 0L);
    }

    /**
     * 按前缀枚举键（{@code SCAN}，不用 {@code KEYS}）。
     *
     * <p>仅供模块 19 的缓存巡检使用。仍然强制 {@code ml:} 前缀——本机 db0 是共享实例，
     * 另一个项目的 {@code ranking:*} 绝对不能出现在本项目的巡检结果里。
     */
    public List<String> keysByPrefix(String prefix, int limit) {
        if (prefix == null || !prefix.startsWith(RedisKeys.PREFIX)) {
            throw new IllegalArgumentException("按前缀扫描只允许操作 " + RedisKeys.PREFIX + " 下的键，收到：" + prefix);
        }
        int max = Math.max(1, Math.min(1000, limit));
        return guard.call("SCAN " + prefix,
                () -> {
                    Iterable<String> keys = redisson.getKeys().getKeysByPattern(prefix + "*", max);
                    List<String> list = new java.util.ArrayList<>();
                    if (keys != null) {
                        for (String key : keys) {
                            list.add(key);
                            if (list.size() >= max) {
                                break;
                            }
                        }
                    }
                    return list;
                }, Collections.<String>emptyList());
    }

    /** 键类型（STRING/HASH/ZSET...）；不存在或 Redis 不可用返回 {@code none}。 */
    public String type(String key) {
        return guard.call("TYPE " + key, () -> {
            RType type = redisson.getKeys().getType(key);
            return type == null ? "none" : type.name();
        }, "none");
    }

    /** 自增并返回自增后的值；Redis 不可用时返回 0。 */
    public long increment(String key, Duration ttlOnFirst) {
        return incrementBy(key, 1L, ttlOnFirst);
    }

    /**
     * 按增量自增并返回自增后的值；Redis 不可用时返回 0。
     *
     * <p>TTL 只在**第一次**写入时设置（{@code v == delta}），窗口因此是固定的。
     * AI 成本累加用它：{@code INCRBY ml:ai:cost:{date} {costCent}}。
     */
    public long incrementBy(String key, long delta, Duration ttlOnFirst) {
        return guard.call("INCRBY " + key, () -> {
            Long value = redis.opsForValue().increment(key, delta);
            long v = value == null ? 0L : value;
            // 只在第一次自增时设 TTL：这样窗口是固定的，而不是「每次失败都续期」
            if (v == delta && ttlOnFirst != null && !ttlOnFirst.isZero()) {
                redis.expire(key, ttlOnFirst);
            }
            return v;
        }, 0L);
    }

    public void addToSet(String key, String value, Duration ttl) {
        guard.run("SADD " + key, () -> {
            redis.opsForSet().add(key, value);
            if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                redis.expire(key, ttl);
            }
        });
    }

    /**
     * {@code SADD} 并返回「本次是否真的新增」。
     *
     * <p>投票去重需要它：先 {@code SISMEMBER} 再 {@code SADD} 有竞态，两个并发请求会都判为
     * 「没投过」；直接以 {@code SADD} 的返回值 0/1 为准才不会重复计票。
     *
     * @return true = 本次新增；Redis 不可用时返回 false（与「已存在」同义，宁可少计一票）
     */
    public boolean addToSetOnce(String key, String value, Duration ttl) {
        return guard.call("SADD " + key, () -> {
            Long added = redis.opsForSet().add(key, value);
            if (added != null && added > 0 && ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                redis.expire(key, ttl);
            }
            return added != null && added > 0;
        }, false);
    }

    public void removeFromSet(String key, String value) {
        guard.run("SREM " + key, () -> redis.opsForSet().remove(key, value));
    }

    public Set<String> members(String key) {
        return guard.call("SMEMBERS " + key, () -> {
            Set<String> members = redis.opsForSet().members(key);
            return members == null ? Collections.<String>emptySet() : members;
        }, Collections.<String>emptySet());
    }

    /**
     * 剩余 TTL（秒）。
     *
     * @return -2 键不存在；-1 无过期时间；0 查询失败（Redis 不可用）
     */
    public long ttlSeconds(String key) {
        return guard.call("TTL " + key, () -> {
            Long ttl = redis.getExpire(key);
            return ttl == null ? 0L : ttl;
        }, 0L);
    }

    // =========================================================================
    // ZSet（模块 06 热搜，系分 D-45）
    //
    // 为什么补在 RedisOps 而不是让搜索模块自己用 StringRedisTemplate：RedisOps 是
    // 「对 Redis 简单数据结构的唯一出口」，它统一经 RedisGuard 熔断、统一守 ml: 前缀。
    // 绕开它等于绕开这两条保证——热搜恰好是「Redis 挂了不该影响主流程」的典型场景，
    // 熔断对它比谁都重要。
    //
    // 全部 add-only，未改任何既有方法签名（模块 03 无回归风险）。
    // =========================================================================

    /**
     * {@code ZINCRBY}，返回累加后的分数。
     *
     * <p><b>只在成员首次出现时设 TTL</b>，与 {@link #increment} 同一个理由：
     * 每次都续期会让这个键永不过期，而热搜榜恰恰需要「冷下来就消失」。
     *
     * @return 累加后的分数；Redis 不可用时返回 {@code 0}
     */
    public double zIncrBy(String key, String member, double delta, Duration ttl) {
        return guard.call("ZINCRBY " + key, () -> {
            Double score = redis.opsForZSet().incrementScore(key, member, delta);
            if (score != null && ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                redis.expire(key, ttl);
            }
            return score == null ? 0d : score;
        }, 0d);
    }

    /**
     * 按分数**从高到低**取区间及其分数。
     *
     * <p>返回 {@code Map} 而不是 {@code List<TypedTuple>}：调用方关心的永远是
     * 「词 → 分数」，而 {@code TypedTuple} 会把 Spring Data 的类型带进业务代码。
     * 用 {@link java.util.LinkedHashMap} 保序，调用方不必再排一次。
     */
    public Map<String, Double> zRevRangeWithScores(String key, long start, long end) {
        return guard.call("ZREVRANGE " + key, () -> {
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    redis.opsForZSet().reverseRangeWithScores(key, start, end);
            if (tuples == null || tuples.isEmpty()) {
                return Collections.<String, Double>emptyMap();
            }
            Map<String, Double> result = new LinkedHashMap<>(tuples.size());
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                if (tuple.getValue() != null) {
                    result.put(tuple.getValue(), tuple.getScore() == null ? 0d : tuple.getScore());
                }
            }
            return result;
        }, Collections.<String, Double>emptyMap());
    }

    /** 单个成员的分数。{@code null} = 不存在或 Redis 不可用（用 {@link #isAvailable()} 区分） */
    public Double zScore(String key, String member) {
        return guard.call("ZSCORE " + key, () -> redis.opsForZSet().score(key, member), null);
    }

    public void zRemove(String key, String... members) {
        guard.run("ZREM " + key, () -> redis.opsForZSet().remove(key, (Object[]) members));
    }

    /**
     * {@code ZADD}。模块 05 的排麦队列按“申请序号”排序，必须在入队时显式给分，
     * {@link #zIncrBy} 的累加语义做不到这个（同一用户重复入队会把分数越加越大）。
     */
    public void zAdd(String key, String member, double score, Duration ttl) {
        if (member == null) {
            return;
        }
        guard.run("ZADD " + key, () -> {
            redis.opsForZSet().add(key, member, score);
            if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                redis.expire(key, ttl);
            }
        });
    }

    /**
     * 把同一个 member/score 写入多个 ZSet（一次 pipeline）。
     *
     * <p>发布动态推送到粉丝收件箱时，5000 个 key 逐个 {@link #zAdd} 是 5000 次 RTT；
     * pipeline 把它压成一次网络往返（BR-25-050）。TTL 用 {@code EXPIRE} 逐 key 设置，
     * 只对首次写入的 key 生效——重复推送不应让收件箱永不过期。
     */
    public void zAddBatch(java.util.Collection<String> keys, String member, double score, Duration ttl) {
        if (keys == null || keys.isEmpty() || member == null) {
            return;
        }
        final List<String> ordered = new java.util.ArrayList<>(keys);
        guard.run("PIPELINE ZADD x" + ordered.size(), () -> {
            redis.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                byte[] m = member.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (String key : ordered) {
                    byte[] k = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    connection.zSetCommands().zAdd(k, score, m);
                    if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                        connection.expire(k, ttl.getSeconds());
                    }
                }
                return null;
            });
        });
    }

    /** {@code ZRANGE} 升序，含两端。不存在或 Redis 不可用时返回空列表。 */
    public List<String> zRange(String key, long start, long end) {
        return guard.call("ZRANGE " + key, () -> {
            Set<String> values = redis.opsForZSet().range(key, start, end);
            return values == null ? Collections.<String>emptyList() : new java.util.ArrayList<>(values);
        }, Collections.<String>emptyList());
    }

    /** {@code ZCARD}。Redis 不可用时返回 0（与空集合同义，调用方按降级处理）。 */
    /** 统计分数区间内的成员数；Redis 不可用返回 0。 */
    public long zCount(String key, double min, double max) {
        return guard.call("ZCOUNT " + key, () -> {
            Long value = redis.opsForZSet().count(key, min, max);
            return value == null ? 0L : value;
        }, 0L);
    }

    public long zCard(String key) {
        return guard.call("ZCARD " + key, () -> {
            Long size = redis.opsForZSet().zCard(key);
            return size == null ? 0L : size;
        }, 0L);
    }

    /**
     * {@code ZREMRANGEBYRANK}：按名次删区间（0 为分数最低）。
     *
     * <p>最近播放要保留 500 条，但 {@link #zAdd} 的分数是时间戳，新的排在高位，
     * 因此调用方用 {@code zRemRangeByRank(key, 0, -501)} 删掉最旧的部分。
     */
    public long zRemRangeByRank(String key, long start, long end) {
        return guard.call("ZREMRANGEBYRANK " + key, () -> {
            Long removed = redis.opsForZSet().removeRange(key, start, end);
            return removed == null ? 0L : removed;
        }, 0L);
    }

    /** {@code SCARD}。Redis 不可用时返回 0。 */
    public long setSize(String key) {
        return guard.call("SCARD " + key, () -> {
            Long size = redis.opsForSet().size(key);
            return size == null ? 0L : size;
        }, 0L);
    }

    /**
     * {@code ZREVRANGE} 降序，含两端。最近播放按时间戳倒序读取用。
     */
    public List<String> zRevRange(String key, long start, long end) {
        return guard.call("ZREVRANGE " + key, () -> {
            Set<String> values = redis.opsForZSet().reverseRange(key, start, end);
            return values == null ? Collections.<String>emptyList() : new java.util.ArrayList<>(values);
        }, Collections.<String>emptyList());
    }

    /** {@code EXPIRE}。用于 Hash 写完后再补 TTL（HSET 本身不带过期时间）。 */
    public boolean expire(String key, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return false;
        }
        return guard.call("EXPIRE " + key, () -> Boolean.TRUE.equals(redis.expire(key, ttl)), false);
    }

    // =========================================================================
    // List（模块 06 搜索历史）
    // =========================================================================

    /** {@code LPUSH} + 可选 TTL。返回推入后的表长（0 表示 Redis 不可用）。 */
    public long listPush(String key, String value, Duration ttl) {
        return guard.call("LPUSH " + key, () -> {
            Long size = redis.opsForList().leftPush(key, value);
            if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                redis.expire(key, ttl);
            }
            return size == null ? 0L : size;
        }, 0L);
    }

    /** {@code LRANGE}（含两端）。Redis 不可用或键不存在时返回空列表 */
    public List<String> listRange(String key, long start, long end) {
        return guard.call("LRANGE " + key, () -> {
            List<String> values = redis.opsForList().range(key, start, end);
            return values == null ? Collections.<String>emptyList() : values;
        }, Collections.<String>emptyList());
    }

    /**
     * {@code LREM}，返回实际删掉的个数。
     *
     * <p>{@code count = 0} 表示「删掉全部匹配值」——搜索历史的「去重置顶」
     * 正是这个语义（先删光旧的同名条目，再推到最前）。
     * {@code count = 1} 表示「从头删一条」，用于用户显式删除某一条。
     */
    public long listRemove(String key, long count, String value) {
        return guard.call("LREM " + key, () -> {
            Long removed = redis.opsForList().remove(key, count, value);
            return removed == null ? 0L : removed;
        }, 0L);
    }

    /** {@code LTRIM}：只保留 {@code [start, end]} 区间 */
    public void listTrim(String key, long start, long end) {
        guard.run("LTRIM " + key, () -> redis.opsForList().trim(key, start, end));
    }

    // =========================================================================
    // SETNX
    // =========================================================================

    /**
     * 键不存在时才写入（{@code SET key value NX EX ttl}）。
     *
     * @return true = 本次写入成功（即**之前不存在**）。
     *         Redis 不可用时返回 <b>false</b>——调用方一律按「已存在」处理，
     *         对热搜去重而言这是「这次不计入」，宁可少计一次也不要重复计数
     */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        return guard.call("SETNX " + key, () -> {
            Boolean ok = redis.opsForValue().setIfAbsent(key, value, ttl);
            return Boolean.TRUE.equals(ok);
        }, false);
    }

    // =========================================================================
    // Hash（模块 04：房间权威播放态 ml:room:play:{roomId} 按 PRD 12 章定义为 Hash）
    // =========================================================================

    /** {@code HSET} 多个字段。Redis 不可用时静默丢弃（与其它写方法一致）。 */
    public void hashSetAll(String key, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        // Redis Hash 没有 null：Redisson 在编码 null 时抛 NPE，而且会把整条命令变成超时。
        // 统一把 null 落成空串，调用方不必逐个字段判空。
        Map<String, String> safe = new LinkedHashMap<>(values.size());
        values.forEach((field, value) -> {
            if (field != null) {
                safe.put(field, value == null ? "" : value);
            }
        });
        guard.run("HMSET " + key, () -> redis.opsForHash().putAll(key, safe));
    }

    /** {@code HGETALL}。不存在或 Redis 不可用时返回空 Map。 */
    public Map<String, String> hashGetAll(String key) {
        return guard.call("HGETALL " + key, () -> {
            Map<Object, Object> raw = redis.opsForHash().entries(key);
            if (raw == null || raw.isEmpty()) {
                return Collections.<String, String>emptyMap();
            }
            Map<String, String> result = new LinkedHashMap<>(raw.size());
            for (Map.Entry<Object, Object> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()),
                            entry.getValue() == null ? null : String.valueOf(entry.getValue()));
                }
            }
            return result;
        }, Collections.<String, String>emptyMap());
    }

    /**
     * 一次 pipeline 读取多个 Hash（{@code HGETALL}）。
     *
     * <p>特征服务要在 20ms 内取回一首歌的十几个特征，以及用户特征 + 上下文特征；
     * 逐个 HGETALL 往返是 N 次 RTT，pipeline 把它压成 1 次（FR-24-005）。
     *
     * <p>返回的 Map 以**请求顺序**为键；不存在或 Redis 不可用的 key 对应空 Map，
     * 调用方按「特征缺失」处理，不要把它当成异常。
     */
    public Map<String, Map<String, String>> hashGetAllBatch(java.util.Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyMap();
        }
        final List<String> ordered = new java.util.ArrayList<>(keys);
        return guard.call("PIPELINE HGETALL x" + ordered.size(), () -> {
            List<Object> results = redis.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                for (String key : ordered) {
                    connection.hashCommands().hGetAll(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return null;
            });
            Map<String, Map<String, String>> result = new LinkedHashMap<>(ordered.size());
            for (int i = 0; i < ordered.size(); i++) {
                Map<String, String> values = new LinkedHashMap<>();
                Object raw = results != null && i < results.size() ? results.get(i) : null;
                if (raw instanceof Map) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                        if (entry.getKey() != null) {
                            values.put(String.valueOf(entry.getKey()),
                                    entry.getValue() == null ? null : String.valueOf(entry.getValue()));
                        }
                    }
                }
                result.put(ordered.get(i), values);
            }
            return result;
        }, Collections.<String, Map<String, String>>emptyMap());
    }

    /** {@code HGET}。字段不存在与 Redis 不可用都返回空 Optional（用 {@link #isAvailable()} 区分）。 */
    public Optional<String> hashGet(String key, String field) {
        return guard.call("HGET " + key + ' ' + field, () -> {
            Object value = redis.opsForHash().get(key, field);
            return Optional.ofNullable(value == null ? null : String.valueOf(value));
        }, Optional.<String>empty());
    }

    /** {@code HINCRBY}，返回累加后的值。TTL 只在首次写入时设置。Redis 不可用时返回 0。 */
    public long hashIncrement(String key, String field, long delta, Duration ttl) {
        if (field == null) {
            return 0L;
        }
        return guard.call("HINCRBY " + key + ' ' + field, () -> {
            Long value = redis.opsForHash().increment(key, field, delta);
            long v = value == null ? 0L : value;
            if (v == delta && ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                redis.expire(key, ttl);
            }
            return v;
        }, 0L);
    }

    public void hashDelete(String key, String... fields) {
        if (fields == null || fields.length == 0) {
            return;
        }
        guard.run("HDEL " + key, () -> redis.opsForHash().delete(key, (Object[]) fields));
    }

    /** {@code SISMEMBER}。Redis 不可用时返回 false（按「不在集合里」处理）。 */
    public boolean isMember(String key, String value) {
        if (value == null) {
            return false;
        }
        return guard.call("SISMEMBER " + key, () -> Boolean.TRUE.equals(redis.opsForSet().isMember(key, value)), false);
    }

    // =========================================================================
    // HyperLogLog（模块 17）
    //
    // UV 类指标（DAU / 访客数）必须用 HLL：12KB 可统计上亿，误差 0.81%
    // （PRD 26 BR-26-049）。精确人数场景仍用 Set（见 addToSetOnce）。
    // =========================================================================

    /**
     * {@code PFADD}。
     *
     * @return true = HLL 内部结构发生变化（新增了元素）；Redis 不可用时 false
     */
    public boolean pfAdd(String key, String value, Duration ttl) {
        if (value == null) {
            return false;
        }
        return guard.call("PFADD " + key, () -> {
            Long added = redis.opsForHyperLogLog().add(key, value);
            if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                redis.expire(key, ttl);
            }
            return added != null && added > 0;
        }, false);
    }

    /** {@code PFCOUNT}；Redis 不可用时返回 0。 */
    public long pfCount(String key) {
        return guard.call("PFCOUNT " + key, () -> {
            Long count = redis.opsForHyperLogLog().size(key);
            return count == null ? 0L : count;
        }, 0L);
    }
}
