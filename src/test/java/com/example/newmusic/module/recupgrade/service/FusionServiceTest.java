package com.example.newmusic.module.recupgrade.service;

import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import com.example.newmusic.module.recupgrade.model.Candidate;
import com.example.newmusic.module.recupgrade.model.RecallItem;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import com.example.newmusic.module.recupgrade.support.RecConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WRF 融合单测（FR-24-002）。
 *
 * <p>用两条召回源验证三件事：RANK 归一化公式、位置衰减、多源共识累加。
 * 期望值是手算的，不是实现跑出来再抄回去——否则「公式错了」和「测试跟着错」会一起通过。
 */
@DisplayName("FusionService 多路融合")
class FusionServiceTest {

    private final RecConfigService configService = mock(RecConfigService.class);
    private final RecallDataService dataService = mock(RecallDataService.class);
    private final RecUpgradeProperties properties = new RecUpgradeProperties();
    private final FusionService fusionService = new FusionService(configService, dataService, properties);

    @Test
    @DisplayName("RANK 归一化 + 衰减 + 多源累加：共识歌曲排第一且 sourceCount=2")
    void fuseByRankWithDecayAndConsensus() {
        Map<Long, Song> catalog = catalog(25);
        when(dataService.playableMap()).thenReturn(catalog);
        when(configService.fusion(anyString())).thenReturn(setting());

        List<RecallResult> results = Arrays.asList(
                RecallResult.ok("cf_user", range(1, 22), 3),
                RecallResult.ok("vector", items(3, 23, 24), 4));
        List<Candidate> fused = fusionService.fuse("HOME_FEED", results);

        Candidate song3 = find(fused, 3L);
        // A 源 rank=2（第 3 位）：(1 - 2/22) * 0.9^2 = 0.73636；B 源 rank=0：1.0
        assertEquals(1.736363636, song3.getFusedScore(), 1e-6);
        assertEquals(2, song3.getSourceCount());
        assertTrue(song3.getSources().containsKey("cf_user"));
        assertTrue(song3.getSources().containsKey("vector"));
        // 共识分数高于任何单源首位
        assertTrue(song3.getFusedScore() > find(fused, 1L).getFusedScore());

        // song2：A 源 rank=1 → (1 - 1/22) * 0.9 = 0.85909
        assertEquals(0.859090909, find(fused, 2L).getFusedScore(), 1e-6);
        // song1：A 源 rank=0 → 1.0
        assertEquals(1.0, find(fused, 1L).getFusedScore(), 1e-6);
        assertEquals(Long.valueOf(3L), fused.get(0).getSongId());
    }

    @Test
    @DisplayName("同输入两次融合顺序完全一致（BR-24-021 确定性）")
    void deterministicOrder() {
        when(dataService.playableMap()).thenReturn(catalog(25));
        when(configService.fusion(anyString())).thenReturn(setting());
        List<RecallResult> results = Arrays.asList(
                RecallResult.ok("cf_user", range(1, 22), 3),
                RecallResult.ok("vector", items(3, 23, 24), 4));

        List<Long> first = ids(fusionService.fuse("HOME_FEED", results));
        List<Long> second = ids(fusionService.fuse("HOME_FEED", results));
        assertEquals(first, second);
    }

    @Test
    @DisplayName("失败的召回源不参与融合")
    void failedSourceIgnored() {
        when(dataService.playableMap()).thenReturn(catalog(25));
        when(configService.fusion(anyString())).thenReturn(setting());
        List<RecallResult> results = Arrays.asList(
                RecallResult.ok("cf_user", range(1, 22), 3),
                RecallResult.failed("vector", 4, "timeout"));
        assertEquals(22, fusionService.fuse("HOME_FEED", results).size());
    }

    // ------------------------------------------------------------------ fixtures

    private RecConfigService.FusionSetting setting() {
        RecConfigService.FusionSetting setting = new RecConfigService.FusionSetting();
        setting.setNormalize("RANK");
        setting.setTopN(500);
        setting.setMinCandidates(20);
        setting.setArtistMax(2);
        setting.setAlbumMax(1);
        setting.setCategoryRatio(0.4);
        setting.setLongTailRatio(0.1);
        RecConfigService.FusionSource a = new RecConfigService.FusionSource();
        a.setName("cf_user");
        a.setWeight(1.0);
        a.setDecay(0.9);
        RecConfigService.FusionSource b = new RecConfigService.FusionSource();
        b.setName("vector");
        b.setWeight(1.0);
        b.setDecay(1.0);
        setting.setSources(Arrays.asList(a, b));
        return setting;
    }

    private List<RecallItem> range(long from, long to) {
        List<RecallItem> items = new ArrayList<>();
        for (long id = from; id <= to; id++) {
            items.add(RecallItem.of(id, 1.0));
        }
        return items;
    }

    private List<RecallItem> items(long... ids) {
        List<RecallItem> items = new ArrayList<>();
        for (long id : ids) {
            items.add(RecallItem.of(id, 1.0));
        }
        return items;
    }

    private Map<Long, Song> catalog(int size) {
        Map<Long, Song> map = new LinkedHashMap<>();
        for (long id = 1; id <= size; id++) {
            Song song = new Song();
            song.setId(id);
            song.setName("song-" + id);
            song.setArtistId(1000 + id);
            song.setAlbumId(2000 + id);
            song.setGenre("G" + id);
            song.setLanguage("L" + id);
            song.setHotScore((int) (1000 - id));
            song.setPlayCount(10000L - id);
            map.put(id, song);
        }
        return map;
    }

    private Candidate find(List<Candidate> candidates, Long songId) {
        return candidates.stream().filter(c -> songId.equals(c.getSongId())).findFirst().orElseThrow(AssertionError::new);
    }

    private List<Long> ids(List<Candidate> candidates) {
        List<Long> ids = new ArrayList<>();
        for (Candidate candidate : candidates) {
            ids.add(candidate.getSongId());
        }
        return ids;
    }
}
