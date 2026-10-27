package com.example.newmusic.module.dict.support;

import com.example.newmusic.module.dict.service.DictSnapshotService;
import com.example.newmusic.module.dict.vo.DictAnalyzeVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 本地分词兜底（ES 不可用时）。
 *
 * <p>它不是 IK 的复刻：IK 有词库 + 歧义消除 + 细/粗粒度两套算法，这里只做
 * **正向最大匹配**。目的很明确——让运营在没有 ES 的开发机上也能验证「加词之后
 * 分词结果变了没有」，以及让验收脚本能断言 AC-20-001-4 / AC-20-113。
 *
 * <p>ES 恢复后，{@code DictAdminService} 会优先走 ES 的 {@code _analyze}，
 * 只有失败/未启用才落到这里，响应里的 {@code source} 字段会如实标记。
 */
@Component
public class LocalDictAnalyzer {

    private final DictSnapshotService snapshotService;

    public LocalDictAnalyzer(DictSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    public DictAnalyzeVO analyze(String text, String analyzer) {
        return analyze(text, analyzer, java.util.Collections.emptySet());
    }

    /**
     * @param extraWords 「修改后」预览词条：后台在保存前试算「加上这些词会怎么分」，
     *                   ES 不可能知道未保存的词，所以预览一律走本地匹配。
     */
    public DictAnalyzeVO analyze(String text, String analyzer, Set<String> extraWords) {
        DictAnalyzeVO vo = new DictAnalyzeVO();
        vo.setText(text);
        vo.setAnalyzer(analyzer);
        vo.setSource("LOCAL");
        vo.setNotice(extraWords == null || extraWords.isEmpty()
                ? "Elasticsearch 未启用或不可用，以下为本地最大匹配结果（近似 IK 分词）"
                : "预览模式：已加入 " + extraWords.size() + " 个尚未保存的词条");
        if (text == null || text.isEmpty()) {
            return vo;
        }

        Set<String> words = new java.util.HashSet<>();
        try {
            words.addAll(snapshotService.get(DictSnapshotService.TYPE_MAIN).getMainWords());
        } catch (RuntimeException e) {
            // 词典表不存在时仍然给逐字结果，而不是 500
        }
        if (extraWords != null) {
            words.addAll(extraWords);
        }

        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isHan(c)) {
                int matched = longest(words, text, i);
                if (matched > 0) {
                    vo.getTokens().add(new DictAnalyzeVO.Token(
                            text.substring(i, i + matched), i, i + matched, "CN_WORD"));
                    i += matched;
                } else {
                    vo.getTokens().add(new DictAnalyzeVO.Token(String.valueOf(c), i, i + 1, "CN_CHAR"));
                    i++;
                }
            } else if (isAsciiAlnum(c)) {
                int start = i;
                boolean digit = Character.isDigit(c);
                while (i < text.length() && isAsciiAlnum(text.charAt(i))) {
                    i++;
                }
                vo.getTokens().add(new DictAnalyzeVO.Token(
                        text.substring(start, i), start, i, digit ? "NUMBER" : "ENGLISH"));
            } else if (Character.isWhitespace(c)) {
                i++;
            } else {
                vo.getTokens().add(new DictAnalyzeVO.Token(String.valueOf(c), i, i + 1, "PUNCTUATION"));
                i++;
            }
        }
        return vo;
    }

    /** 正向最大匹配；返回 0 表示未命中词典。 */
    private int longest(Set<String> words, String text, int start) {
        int max = Math.min(15, text.length() - start);
        for (int len = max; len >= 2; len--) {
            if (words.contains(text.substring(start, start + len))) {
                return len;
            }
        }
        return 0;
    }

    private static boolean isHan(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
