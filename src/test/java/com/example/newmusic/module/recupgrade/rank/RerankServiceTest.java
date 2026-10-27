package com.example.newmusic.module.recupgrade.rank;

import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import com.example.newmusic.module.recupgrade.model.Candidate;
import com.example.newmusic.module.recupgrade.model.RecTrace;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import com.example.newmusic.module.recupgrade.support.RecConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 重排算子链单测（FR-24-004）。
 *
 * <p>这里守的是置顶恢复的顺序敏感性：BOOST_NEW 会把置顶歌曲重新排序，恢复时若逐个
 * 「移除→插入」，后一个置顶会把前一个挤左一位（目标第 8 位落到第 7 位）。期望位置是手算的。
 */
@DisplayName("RerankService 重排链")
class RerankServiceTest {

    private final RecConfigService configService = mock(RecConfigService.class);
    private final RecallDataService dataService = mock(RecallDataService.class);
    private final ExploreService exploreService = mock(ExploreService.class);
    private final RecUpgradeProperties properties = new RecUpgradeProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RerankService rerankService =
            new RerankService(configService, dataService, exploreService, properties);

    @Test
    @DisplayName("置顶恢复：后面的置顶原本排在最前，也不会把前一个置顶挤偏")
    void restorePinsIsOrderIndependent() throws Exception {
        when(configService.rerank(anyString())).thenReturn(Arrays.asList(
                operator("PIN", "{\"positions\":[1,8,15],\"source\":\"editor\"}"),
                operator("BOOST_NEW", "{\"days\":7,\"boost\":1.15}"),
                operator("TRUNCATE", "{\"size\":12}")));

        List<Candidate> input = new ArrayList<>();
        for (long id = 1; id <= 12; id++) {
            Candidate candidate = new Candidate();
            candidate.setSongId(id);
            // 三个编辑精选分别放在第 5、9、11 位，排序后第 11 位会跑到最前面
            candidate.setEditor(id == 5 || id == 9 || id == 11);
            candidate.setFineScore(id == 11 ? 0.99 : id == 9 ? 0.5 : id == 5 ? 0.1 : 0.2);
            input.add(candidate);
        }

        List<Candidate> result = rerankService.rerank("HOME_FEED", input, null, new RecTrace());

        assertEquals(12, result.size());
        assertEquals(Long.valueOf(5L), result.get(0).getSongId(), "第 1 位置顶");
        assertEquals(Long.valueOf(9L), result.get(7).getSongId(), "第 8 位置顶（不能被挤到第 7 位）");
    }

    private RecConfigService.OperatorSetting operator(String type, String raw) throws Exception {
        RecConfigService.OperatorSetting setting = new RecConfigService.OperatorSetting();
        setting.setType(type);
        setting.setRaw(objectMapper.readTree(raw));
        return setting;
    }
}
