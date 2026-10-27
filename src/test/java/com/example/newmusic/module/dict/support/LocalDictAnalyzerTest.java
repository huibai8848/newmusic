package com.example.newmusic.module.dict.support;

import com.example.newmusic.module.dict.service.DictSnapshotService;
import com.example.newmusic.module.dict.vo.DictAnalyzeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本地分词兜底单测（模块 11 FR-20-001 / BR-20-060）。
 *
 * <p>没有 ES 时后台仍然要能回答「加这个词之后会怎么分」。这里钉住三件事：
 * 预览词立即生效、偏移量正确、词典表缺失时退化为逐字且不抛异常。
 */
@DisplayName("LocalDictAnalyzer 本地最大匹配")
class LocalDictAnalyzerTest {

    private DictSnapshotService snapshot;
    private LocalDictAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        snapshot = mock(DictSnapshotService.class);
        // 模拟「词典表缺失」：快照服务抛错，分析器必须吞掉并继续
        when(snapshot.get(DictSnapshotService.TYPE_MAIN))
                .thenThrow(new IllegalStateException("table missing"));
        analyzer = new LocalDictAnalyzer(snapshot);
    }

    @Test
    @DisplayName("预览词条参与分词，并按最长匹配切出完整词")
    void previewWordParticipates() {
        DictAnalyzeVO vo = analyzer.analyze("量子音乐现场", "ik_smart",
                Collections.singleton("量子音乐"));

        List<DictAnalyzeVO.Token> tokens = vo.getTokens();
        DictAnalyzeVO.Token first = tokens.get(0);
        assertEquals("量子音乐", first.getToken());
        assertEquals(0, first.getStartOffset());
        assertEquals(4, first.getEndOffset());
        assertEquals("CN_WORD", first.getType());
        assertTrue(vo.getNotice().contains("预览模式"));
        assertEquals("LOCAL", vo.getSource());
    }

    @Test
    @DisplayName("没有命中词典时退化为逐字，偏移量连续")
    void unmatchedFallsBackToSingleChars() {
        DictAnalyzeVO vo = analyzer.analyze("晴天", "ik_smart", Collections.emptySet());

        List<String> tokens = vo.getTokens().stream()
                .map(DictAnalyzeVO.Token::getToken)
                .collect(Collectors.toList());
        assertEquals(2, tokens.size());
        assertEquals("晴", tokens.get(0));
        assertEquals("天", tokens.get(1));
        assertEquals(0, vo.getTokens().get(0).getStartOffset());
        assertEquals(2, vo.getTokens().get(1).getEndOffset());
        assertFalse(vo.getNotice().contains("预览模式"));
    }

    @Test
    @DisplayName("英文与数字按连续段切分，空白丢弃")
    void asciiRuns() {
        DictAnalyzeVO vo = analyzer.analyze("abc 123", "ik_smart", Collections.emptySet());

        List<DictAnalyzeVO.Token> tokens = vo.getTokens();
        assertEquals(2, tokens.size());
        assertEquals("abc", tokens.get(0).getToken());
        assertEquals("ENGLISH", tokens.get(0).getType());
        assertEquals("123", tokens.get(1).getToken());
        assertEquals("NUMBER", tokens.get(1).getType());
    }

    @Test
    @DisplayName("预览词超过 15 字符时不参与匹配（与 IK 的上限一致）")
    void previewWordLongerThanLimitIsIgnored() {
        Set<String> preview = Collections.singleton("一二三四五六七八九十一二三四五六");
        DictAnalyzeVO vo = analyzer.analyze("一二三四五六七八九十一二三四五六", "ik_smart", preview);

        assertEquals(16, vo.getTokens().size(), "超长词不匹配，逐字输出");
    }
}
