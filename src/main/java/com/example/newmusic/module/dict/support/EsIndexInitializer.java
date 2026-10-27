package com.example.newmusic.module.dict.support;

import com.example.newmusic.module.dict.config.DictProperties;
import com.example.newmusic.module.dict.service.EsReindexService;
import com.example.newmusic.module.dict.vo.DictSyncResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 索引自动初始化（模块 11）。
 *
 * <p>「装上 ES 还要记得去后台点一次重建索引」是很差的首次体验，忘了点就会看到
 * 搜索一直降级。这里在启动时做一次幂等检查：
 * <ul>
 *   <li>别名不存在 → 全量重建（首次启动）；</li>
 *   <li>别名的 {@code _meta.schemaVersion} 低于 {@code newmusic.dict.es.schema-version} →
 *       重建（mapping 升级）；</li>
 *   <li>版本一致 → 什么都不做。</li>
 * </ul>
 *
 * <p>**失败不阻塞启动**：重建失败只打 ERROR，搜索会自动降级到 MySQL，
 * 运营仍可在后台手动重试。ES 未启用或不可达时直接跳过。
 */
@Slf4j
@Component
public class EsIndexInitializer implements ApplicationRunner {

    private final EsClient esClient;
    private final EsReindexService reindexService;
    private final DictProperties properties;

    public EsIndexInitializer(EsClient esClient, EsReindexService reindexService,
                              DictProperties properties) {
        this.esClient = esClient;
        this.reindexService = reindexService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        DictProperties.Es es = properties.getEs();
        if (!es.isEnabled() || !es.isAutoInit()) {
            return;
        }
        if (!esClient.isAvailable()) {
            log.warn("ES 索引自动初始化跳过：{} 不可达（搜索降级为 MySQL）", es.getUrl());
            return;
        }
        String alias = es.getIndexAlias();
        try {
            if (esClient.indexExists(alias)) {
                Integer actual = esClient.indexSchemaVersion(alias);
                int expected = es.getSchemaVersion();
                if (actual != null && actual >= expected) {
                    log.info("ES 索引就绪：别名={} schemaVersion={} 版本={}",
                            alias, actual, esClient.getVersion());
                    return;
                }
                log.warn("ES 索引 mapping 版本落后（当前={} 期望={}），自动重建别名 {}",
                        actual, expected, alias);
            } else {
                log.info("ES 别名 {} 不存在，首次构建索引", alias);
            }
            DictSyncResultVO result = reindexService.reindex();
            log.info("ES 索引自动初始化完成：{}（扫描 {}，写入 {}，耗时 {}ms）",
                    result.getMessage(), result.getScanned(), result.getCreated(), result.getCostMs());
        } catch (RuntimeException e) {
            // 自动初始化是便利功能，不是启动前置条件；失败要让服务照常起来
            log.error("ES 索引自动初始化失败，搜索将降级到 MySQL：{}", e.toString());
        }
    }
}
