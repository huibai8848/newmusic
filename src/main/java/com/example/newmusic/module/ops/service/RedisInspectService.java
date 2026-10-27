package com.example.newmusic.module.ops.service;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 缓存巡检（FR-28-010 / AC-28-010-3）。
 *
 * <p><b>共享实例红线：</b>本机 6379 的 db0 还有另一个项目的 {@code ranking:*} 键。
 * 所以这里做了三道闸：
 * <ol>
 *   <li>前缀必须以 {@code ml:} 开头；</li>
 *   <li>枚举只用 {@code SCAN}（Redisson getKeysByPattern），绝不用 {@code KEYS}；</li>
 *   <li>删除要求前缀至少含两个冒号（如 {@code ml:search:}），且 {@code confirm} 必须与前缀完全一致，
 *       因此 {@code ml:} / {@code ml:search} 这类大范围前缀无法被「顺手」清掉。</li>
 * </ol>
 */
@Slf4j
@Service
public class RedisInspectService {

    private final RedisOps redisOps;

    public RedisInspectService(RedisOps redisOps) {
        this.redisOps = redisOps;
    }

    public Map<String, Object> inspect(String prefix, int limit) {
        String normalized = normalize(prefix);
        int max = Math.max(1, Math.min(500, limit));
        List<String> keys = redisOps.keysByPrefix(normalized, max);
        List<Map<String, Object>> items = new ArrayList<>();
        for (String key : keys) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            item.put("type", redisOps.type(key));
            item.put("ttlSeconds", redisOps.ttlSeconds(key));
            items.add(item);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("prefix", normalized);
        map.put("count", items.size());
        map.put("truncated", items.size() >= max);
        map.put("redisAvailable", redisOps.isAvailable());
        map.put("keys", items);
        return map;
    }

    /**
     * 按前缀清理。确认串必须等于前缀——这是给操作人「二次确认」用的，不是装饰。
     *
     * @return 实际删除数；Redis 不可用时为 0
     */
    public long deleteByPrefix(String prefix, String confirm) {
        String normalized = normalize(prefix);
        if (colonCount(normalized) < 2) {
            throw new BizException(ResultCode.OPS_CACHE_PREFIX_FORBIDDEN,
                    "清理前缀至少需要两段，例如 ml:search:，禁止清理 " + normalized);
        }
        if (!normalized.equals(confirm)) {
            throw new BizException(ResultCode.OPS_CACHE_PREFIX_FORBIDDEN,
                    "confirm 必须与前缀完全一致");
        }
        long deleted = redisOps.deleteByPrefix(normalized);
        log.warn("缓存前缀清理 prefix={} deleted={}", normalized, deleted);
        return deleted;
    }

    public String normalize(String prefix) {
        String value = StringUtils.hasText(prefix) ? prefix.trim() : RedisKeys.PREFIX;
        if (!value.startsWith(RedisKeys.PREFIX)) {
            throw new BizException(ResultCode.OPS_CACHE_PREFIX_FORBIDDEN,
                    "只允许巡检 " + RedisKeys.PREFIX + " 下的键");
        }
        if (value.contains("*") || value.contains("?") || value.contains(" ")) {
            throw new BizException(ResultCode.OPS_CACHE_PREFIX_FORBIDDEN,
                    "前缀不能包含通配符或空格");
        }
        return value;
    }

    private static int colonCount(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == ':') {
                count++;
            }
        }
        return count;
    }
}
