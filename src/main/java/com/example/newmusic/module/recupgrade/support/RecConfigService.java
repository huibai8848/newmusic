package com.example.newmusic.module.recupgrade.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.recupgrade.entity.RecConfig;
import com.example.newmusic.module.recupgrade.mapper.RecConfigMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 推荐配置读取与版本管理。
 *
 * <p>配置按 {@code scene + config_type + version} 存表，同一时间只有一行
 * {@code status=1}。读取时走 10 秒本地缓存；后台修改会立即清本地与 Redis 缓存，
 * 所以单实例上的「改完立刻生效」是确定的（BR-24-017 的热更新）。
 *
 * <p>配置缺失/损坏一律回退到内置默认值（EX-24-001-6 / EX-24-002-1），
 * 推荐主流程绝不因为一条坏配置而失败。
 */
@Slf4j
@Service
public class RecConfigService {

    public static final String TYPE_RECALL = "RECALL";
    public static final String TYPE_FUSION = "FUSION";
    public static final String TYPE_RANK = "RANK";
    public static final String TYPE_RERANK = "RERANK";
    public static final String TYPE_EXPERIMENT = "EXPERIMENT";

    private static final long CACHE_MILLIS = 10_000L;

    private static final String DEFAULT_RECALL = "{\"sources\":["
            + "{\"name\":\"hot\",\"enabled\":true,\"limit\":500},"
            + "{\"name\":\"cf_user\",\"enabled\":true,\"limit\":300},"
            + "{\"name\":\"cf_item\",\"enabled\":true,\"limit\":300},"
            + "{\"name\":\"vector\",\"enabled\":true,\"limit\":300},"
            + "{\"name\":\"vector_song\",\"enabled\":true,\"limit\":200},"
            + "{\"name\":\"tag_match\",\"enabled\":true,\"limit\":300},"
            + "{\"name\":\"follow_artist\",\"enabled\":true,\"limit\":200},"
            + "{\"name\":\"friend_listen\",\"enabled\":true,\"limit\":200},"
            + "{\"name\":\"new_song\",\"enabled\":true,\"limit\":200},"
            + "{\"name\":\"local\",\"enabled\":true,\"limit\":100},"
            + "{\"name\":\"recent_play\",\"enabled\":true,\"limit\":100},"
            + "{\"name\":\"long_tail\",\"enabled\":true,\"limit\":200},"
            + "{\"name\":\"editor\",\"enabled\":true,\"limit\":50},"
            + "{\"name\":\"playlist_match\",\"enabled\":true,\"limit\":200},"
            + "{\"name\":\"emotion_match\",\"enabled\":true,\"limit\":200}]}";

    private static final String DEFAULT_FUSION = "{\"normalize\":\"RANK\",\"topN\":500,"
            + "\"minCandidates\":20,\"artistMax\":2,\"albumMax\":1,\"categoryRatio\":0.4,"
            + "\"longTailRatio\":0.10,\"sources\":["
            + "{\"name\":\"cf_user\",\"weight\":1.2,\"limit\":300,\"decay\":0.98},"
            + "{\"name\":\"vector\",\"weight\":1.0,\"limit\":300,\"decay\":0.98},"
            + "{\"name\":\"cf_item\",\"weight\":0.9,\"limit\":300,\"decay\":0.98},"
            + "{\"name\":\"vector_song\",\"weight\":0.9,\"limit\":200,\"decay\":0.95},"
            + "{\"name\":\"tag_match\",\"weight\":0.8,\"limit\":300,\"decay\":0.95},"
            + "{\"name\":\"hot\",\"weight\":0.6,\"limit\":500,\"decay\":0.90},"
            + "{\"name\":\"friend_listen\",\"weight\":0.8,\"limit\":200,\"decay\":0.95},"
            + "{\"name\":\"follow_artist\",\"weight\":0.9,\"limit\":200,\"decay\":0.95},"
            + "{\"name\":\"new_song\",\"weight\":0.7,\"limit\":200,\"decay\":0.95},"
            + "{\"name\":\"long_tail\",\"weight\":0.4,\"limit\":200,\"decay\":0.90},"
            + "{\"name\":\"editor\",\"weight\":2.0,\"limit\":50,\"decay\":1.00},"
            + "{\"name\":\"local\",\"weight\":0.5,\"limit\":100,\"decay\":0.90},"
            + "{\"name\":\"recent_play\",\"weight\":0.7,\"limit\":100,\"decay\":0.95},"
            + "{\"name\":\"playlist_match\",\"weight\":0.7,\"limit\":200,\"decay\":0.95},"
            + "{\"name\":\"emotion_match\",\"weight\":0.6,\"limit\":200,\"decay\":0.95}]}";

    private static final String DEFAULT_RANK = "{\"coarse\":{\"weights\":{\"recall\":0.30,"
            + "\"hot\":0.15,\"tag\":0.15,\"artist\":0.10,\"freshness\":0.10,"
            + "\"completion\":0.08,\"skip\":0.07},\"target\":150,\"skipBelow\":30},"
            + "\"fine\":{\"a\":0.5,\"b\":0.3,\"c\":0.15,\"d\":0.05,"
            + "\"modelVersion\":\"rule-lr-v1\"}}";

    private static final String DEFAULT_RERANK = "{\"operators\":["
            + "{\"type\":\"FILTER_OFFLINE\"},{\"type\":\"DEDUP\"},"
            + "{\"type\":\"PIN\",\"positions\":[1,8,15],\"source\":\"editor\"},"
            + "{\"type\":\"DIVERSIFY_ARTIST\",\"window\":8,\"maxCount\":2},"
            + "{\"type\":\"DIVERSIFY_ALBUM\",\"maxCount\":1},"
            + "{\"type\":\"DIVERSIFY_TAG\",\"maxRatio\":0.4},"
            + "{\"type\":\"BOOST_NEW\",\"days\":7,\"boost\":1.15},"
            + "{\"type\":\"LONGTAIL_INSERT\",\"interval\":10,\"minRatio\":0.10},"
            + "{\"type\":\"EXPLORE_INSERT\",\"ratio\":0.10},"
            + "{\"type\":\"TRUNCATE\",\"size\":30}]}";

    private static final String DEFAULT_EXPERIMENT =
            "{\"minSample\":10000,\"alpha\":0.05,\"minRunDays\":7}";

    private final RecConfigMapper configMapper;
    private final ObjectMapper objectMapper;
    private final RedisOps redisOps;

    private final Map<String, CachedConfig> cache = new ConcurrentHashMap<>();

    public RecConfigService(RecConfigMapper configMapper, ObjectMapper objectMapper, RedisOps redisOps) {
        this.configMapper = configMapper;
        this.objectMapper = objectMapper;
        this.redisOps = redisOps;
    }

    // ------------------------------------------------------------------ 读取

    public RecallSetting recall(String scene) {
        return parse(scene, TYPE_RECALL, DEFAULT_RECALL, RecallSetting.class);
    }

    public FusionSetting fusion(String scene) {
        return parse(scene, TYPE_FUSION, DEFAULT_FUSION, FusionSetting.class);
    }

    public RankSetting rank(String scene) {
        return parse(scene, TYPE_RANK, DEFAULT_RANK, RankSetting.class);
    }

    public List<OperatorSetting> rerank(String scene) {
        JsonNode node = json(scene, TYPE_RERANK, DEFAULT_RERANK);
        JsonNode operators = node.path("operators");
        List<OperatorSetting> result = new ArrayList<>();
        if (operators.isArray()) {
            for (JsonNode item : operators) {
                OperatorSetting setting = new OperatorSetting();
                setting.setType(item.path("type").asText(""));
                setting.setRaw(item);
                result.add(setting);
            }
        }
        if (result.isEmpty()) {
            result = parseJson(DEFAULT_RERANK).path("operators").isArray()
                    ? rerankOperators(parseJson(DEFAULT_RERANK)) : Collections.<OperatorSetting>emptyList();
        }
        return result;
    }

    public ExperimentSetting experiment() {
        return parse("GLOBAL", TYPE_EXPERIMENT, DEFAULT_EXPERIMENT, ExperimentSetting.class);
    }

    public JsonNode json(String scene, String type, String defaultJson) {
        String cacheKey = scene + ':' + type;
        long now = System.currentTimeMillis();
        CachedConfig cached = cache.get(cacheKey);
        if (cached != null && now - cached.loadedAt < CACHE_MILLIS) {
            return cached.node;
        }
        String raw = null;
        try {
            RecConfig row = activeRow(scene, type);
            if (row != null && row.getConfigJson() != null && !row.getConfigJson().trim().isEmpty()) {
                raw = row.getConfigJson();
            }
        } catch (RuntimeException e) {
            log.warn("读取推荐配置失败，使用默认值 scene={} type={} cause={}", scene, type, e.toString());
        }
        JsonNode node = parseJson(raw == null ? defaultJson : raw);
        if (node == null) {
            node = parseJson(defaultJson);
        }
        cache.put(cacheKey, new CachedConfig(node, now));
        return node;
    }

    public RecConfig activeRow(String scene, String type) {
        return configMapper.selectOne(Wrappers.<RecConfig>lambdaQuery()
                .eq(RecConfig::getScene, scene)
                .eq(RecConfig::getConfigType, type)
                .eq(RecConfig::getStatus, RecConfig.STATUS_ACTIVE)
                .orderByDesc(RecConfig::getVersion)
                .last("LIMIT 1"));
    }

    public List<RecConfig> history(String scene, String type) {
        return configMapper.selectList(Wrappers.<RecConfig>lambdaQuery()
                .eq(RecConfig::getScene, scene)
                .eq(RecConfig::getConfigType, type)
                .orderByDesc(RecConfig::getVersion));
    }

    // ------------------------------------------------------------------ 写入

    /** 写入新版本并把旧版本置为历史。返回新版本号。 */
    public int update(String scene, String type, String configJson, Long operatorId) {
        if (configJson == null || configJson.trim().isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "configJson 不能为空");
        }
        JsonNode node = parseJson(configJson);
        if (node == null || !node.isObject()) {
            throw new BizException(ResultCode.PARAM_INVALID, "configJson 必须是合法的 JSON 对象");
        }
        validate(type, node);

        List<RecConfig> rows = history(scene, type);
        int maxVersion = 0;
        for (RecConfig row : rows) {
            if (row.getVersion() != null && row.getVersion() > maxVersion) {
                maxVersion = row.getVersion();
            }
            if (Integer.valueOf(RecConfig.STATUS_ACTIVE).equals(row.getStatus())) {
                row.setStatus(RecConfig.STATUS_HISTORY);
                configMapper.updateById(row);
            }
        }
        RecConfig fresh = new RecConfig();
        fresh.setScene(scene);
        fresh.setConfigType(type);
        fresh.setConfigJson(node.toString());
        fresh.setVersion(maxVersion + 1);
        fresh.setStatus(RecConfig.STATUS_ACTIVE);
        fresh.setOperatorId(operatorId);
        configMapper.insert(fresh);
        invalidate(scene, type);
        return fresh.getVersion();
    }

    /** 回滚到指定历史版本：目标版本置为生效，当前版本置为历史。 */
    public RecConfig rollback(String scene, String type, int version, Long operatorId) {
        RecConfig target = configMapper.selectOne(Wrappers.<RecConfig>lambdaQuery()
                .eq(RecConfig::getScene, scene)
                .eq(RecConfig::getConfigType, type)
                .eq(RecConfig::getVersion, version)
                .last("LIMIT 1"));
        if (target == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "配置版本不存在：" + version);
        }
        for (RecConfig row : history(scene, type)) {
            if (Integer.valueOf(RecConfig.STATUS_ACTIVE).equals(row.getStatus())) {
                row.setStatus(RecConfig.STATUS_HISTORY);
                configMapper.updateById(row);
            }
        }
        target.setStatus(RecConfig.STATUS_ACTIVE);
        target.setOperatorId(operatorId);
        configMapper.updateById(target);
        invalidate(scene, type);
        return target;
    }

    /** 直接覆盖内置默认配置（离线评估等内部场景用，不经过后台版本管理）。 */
    public void invalidate(String scene, String type) {
        cache.remove(scene + ':' + type);
        redisOps.delete(RedisKeys.recUpgradeConfig(scene, type));
    }

    public void invalidateScene(String scene) {
        for (String type : new String[]{TYPE_RECALL, TYPE_FUSION, TYPE_RANK, TYPE_RERANK}) {
            invalidate(scene, type);
        }
    }

    // ------------------------------------------------------------------ 内部

    private void validate(String type, JsonNode node) {
        if (TYPE_FUSION.equals(type)) {
            JsonNode sources = node.path("sources");
            if (!sources.isArray() || sources.size() == 0) {
                throw new BizException(ResultCode.PARAM_INVALID, "FUSION 配置必须包含非空 sources 数组");
            }
            for (JsonNode item : sources) {
                if (!item.path("name").isTextual()) {
                    throw new BizException(ResultCode.PARAM_INVALID, "FUSION sources[*].name 必填");
                }
                if (item.path("weight").asDouble(-1) < 0) {
                    throw new BizException(ResultCode.PARAM_INVALID, "FUSION 权重必须 ≥ 0");
                }
            }
        } else if (TYPE_RERANK.equals(type)) {
            JsonNode operators = node.path("operators");
            if (!operators.isArray() || operators.size() == 0) {
                throw new BizException(ResultCode.PARAM_INVALID, "RERANK 配置必须包含非空 operators 数组");
            }
        } else if (TYPE_RECALL.equals(type)) {
            if (!node.path("sources").isArray()) {
                throw new BizException(ResultCode.PARAM_INVALID, "RECALL 配置必须包含 sources 数组");
            }
        } else if (TYPE_EXPERIMENT.equals(type)) {
            if (node.path("minSample").asInt(0) <= 0) {
                throw new BizException(ResultCode.PARAM_INVALID, "EXPERIMENT.minSample 必须 > 0");
            }
        }
    }

    private <T> T parse(String scene, String type, String defaultJson, Class<T> clazz) {
        JsonNode node = json(scene, type, defaultJson);
        try {
            return objectMapper.treeToValue(node, clazz);
        } catch (Exception e) {
            log.warn("推荐配置解析失败，回退默认值 scene={} type={} cause={}", scene, type, e.toString());
            try {
                return objectMapper.treeToValue(parseJson(defaultJson), clazz);
            } catch (Exception ignored) {
                throw new IllegalStateException("内置默认配置无法解析 " + type, ignored);
            }
        }
    }

    private JsonNode parseJson(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("推荐配置 JSON 非法：{}", e.toString());
            return null;
        }
    }

    private List<OperatorSetting> rerankOperators(JsonNode node) {
        List<OperatorSetting> result = new ArrayList<>();
        JsonNode operators = node.path("operators");
        if (operators.isArray()) {
            for (JsonNode item : operators) {
                OperatorSetting setting = new OperatorSetting();
                setting.setType(item.path("type").asText(""));
                setting.setRaw(item);
                result.add(setting);
            }
        }
        return result;
    }

    private static class CachedConfig {
        private final JsonNode node;
        private final long loadedAt;

        private CachedConfig(JsonNode node, long loadedAt) {
            this.node = node;
            this.loadedAt = loadedAt;
        }
    }

    // ------------------------------------------------------------------ 配置模型

    @Data
    public static class RecallSetting {
        private List<SourceSetting> sources = new ArrayList<>();
    }

    @Data
    public static class SourceSetting {
        private String name;
        private boolean enabled = true;
        private int limit = 300;
        private Integer timeoutMs;
    }

    @Data
    public static class FusionSetting {
        private String normalize = "RANK";
        private int topN = 500;
        private int minCandidates = 20;
        private int artistMax = 2;
        private int albumMax = 1;
        private double categoryRatio = 0.4;
        private double longTailRatio = 0.10;
        private List<FusionSource> sources = new ArrayList<>();
    }

    @Data
    public static class FusionSource {
        private String name;
        private double weight = 1.0;
        private int limit = 300;
        private double decay = 0.95;
    }

    @Data
    public static class RankSetting {
        private CoarseSetting coarse = new CoarseSetting();
        private FineSetting fine = new FineSetting();
    }

    @Data
    public static class CoarseSetting {
        private Map<String, Double> weights = defaultWeights();
        private int target = 150;
        private int skipBelow = 30;

        private static Map<String, Double> defaultWeights() {
            Map<String, Double> map = new LinkedHashMap<>();
            map.put("recall", 0.30);
            map.put("hot", 0.15);
            map.put("tag", 0.15);
            map.put("artist", 0.10);
            map.put("freshness", 0.10);
            map.put("completion", 0.08);
            map.put("skip", 0.07);
            return map;
        }
    }

    @Data
    public static class FineSetting {
        private double a = 0.5;
        private double b = 0.3;
        private double c = 0.15;
        private double d = 0.05;
        private String modelVersion = "rule-lr-v1";
    }

    @Data
    public static class OperatorSetting {
        private String type;
        private JsonNode raw;
    }

    @Data
    public static class ExperimentSetting {
        private int minSample = 10000;
        private double alpha = 0.05;
        private int minRunDays = 7;
    }
}
