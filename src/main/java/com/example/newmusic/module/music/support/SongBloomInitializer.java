package com.example.newmusic.module.music.support;

import com.example.newmusic.infra.cache.BloomSupport;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.module.music.service.SongService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时用现有歌曲 ID 预热布隆过滤器（FR-12-009 9.1）。
 *
 * <p>不预热不行：布隆过滤器是「加入过才知道存在」，一个空的过滤器会对**所有**歌曲
 * 回答「一定不存在」，于是整个曲库变成 404。预热就是把「已知存在的集合」补进去。
 *
 * <p>三个刻意的选择：
 * <ul>
 *   <li>放在 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}——此时应用已经能对外服务，
 *       预热慢一点也不会阻塞端口监听；</li>
 *   <li>预热只是「重复 add」，而 add 是幂等的，所以每次启动都跑一遍是安全的，
 *       不必去判断「过滤器是不是已经建好了」；</li>
 *   <li>失败只记日志，不抛异常。预热失败最坏的结果是缓存重建多回源几次库，
 *       而挡住启动的代价是整个服务不可用——不划算。</li>
 * </ul>
 */
@Slf4j
@Component
public class SongBloomInitializer {

    private final SongService songService;
    private final BloomSupport bloomSupport;

    public SongBloomInitializer(SongService songService, BloomSupport bloomSupport) {
        this.songService = songService;
        this.bloomSupport = bloomSupport;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!bloomSupport.isEnabled()) {
            log.info("布隆过滤器已关闭（newmusic.infra.cache.bloom.enabled=false），跳过预热");
            return;
        }
        try {
            List<Long> ids = songService.listAllIdsForBloom();
            for (Long id : ids) {
                bloomSupport.add(RedisKeys.BLOOM_SONG, String.valueOf(id));
            }
            log.info("布隆过滤器预热完成，歌曲数={}", ids.size());
        } catch (Exception e) {
            log.error("布隆过滤器预热失败（不影响启动；影响是详情请求会多回源一次库）: {}", e.toString());
        }
    }
}
