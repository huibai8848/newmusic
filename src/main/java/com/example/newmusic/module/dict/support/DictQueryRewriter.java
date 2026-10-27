package com.example.newmusic.module.dict.support;

import com.example.newmusic.module.dict.service.DictSnapshotService;
import com.example.newmusic.module.search.support.PinyinSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 查询改写（模块 11 的应用层落地）。
 *
 * <p>ES 在的时候，这些规则配在 {@code search_analyzer} 上（ik_smart + synonym + stconvert）；
 * ES 不在时（本机默认就是这种情况），同一个语义要有**应用层等价物**，否则模块 11 的
 * P0 能力会变成「只有装 ES 才能用」。这里做四件事：
 *
 * <ol>
 *   <li>全角/半角归一化（BR-20-054）；</li>
 *   <li>繁体 → 简体（BR-20-042，查询时转换，展示保留原文）；</li>
 *   <li>停用词只裁**首尾**，绝不裁中间——「稻香」里的「香」、「歌」这种单字
 *       如果按 IK 的方式全局删除，会把完整歌名拆坏（BR-20-017 的反面约束）；</li>
 *   <li>同义词：仅当**整个关键词**等于某个词时改写为主词，避免「周董 稻香」这种
 *       多词查询被拼成 MySQL LIKE 永远匹配不到的长串。</li>
 * </ol>
 */
@Slf4j
@Component
public class DictQueryRewriter {

    /** 首尾停用词最多裁几轮（"我想听周杰伦的歌" → 4 轮足够） */
    private static final int MAX_TRIM_ROUNDS = 5;
    /** 裁完至少还剩 2 个字符，避免「的确」被单字停用词「的」削成「确」 */
    private static final int MIN_REMAIN = 2;

    private final DictSnapshotService snapshotService;

    public DictQueryRewriter(DictSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    public RewriteResult rewrite(String raw) {
        RewriteResult result = new RewriteResult();
        result.original = raw;
        result.keyword = raw;

        String normalized = DictTextSupport.normalize(raw);
        result.normalized = normalized;
        if (normalized == null || normalized.isEmpty()) {
            return result;
        }

        String simple = DictTextSupport.toSimple(normalized);
        result.traditionalConverted = !simple.equals(normalized);
        if (simple.isEmpty()) {
            simple = normalized;
        }

        DictSnapshotService.QueryRules rules;
        try {
            rules = snapshotService.queryRules();
        } catch (RuntimeException e) {
            // 词典表还没建（存量库未执行 11-ik-seed.sql）时搜索不能 500。
            // 繁简转换不依赖词典表，仍然生效；只是同义词/停用词/多音字别名不可用。
            log.warn("词典规则不可用，按简体原文检索：{}", e.toString());
            result.keyword = simple;
            result.changed = !simple.equals(raw);
            result.pinyinKeyword = PinyinSupport.toSearchPinyin(simple);
            return result;
        }

        String trimmed = trimStopwords(simple, rules.getStopwords());
        result.stopwordTrimmed = !trimmed.equals(simple);
        if (trimmed.isEmpty()) {
            trimmed = simple;
            result.stopwordTrimmed = false;
        }

        String keyword = trimmed;
        String primary = rules.getSynonyms().get(key(keyword));
        if (primary != null && !primary.equals(keyword)) {
            keyword = primary;
            result.synonymApplied = true;
        }
        result.keyword = keyword;

        if (PinyinSupport.isAsciiOnly(keyword)) {
            String mapped = rules.getPinyinAliases().get(keyword.toLowerCase(Locale.ROOT));
            result.pinyinKeyword = PinyinSupport.toSearchPinyin(mapped == null ? keyword : mapped);
            result.pinyinAlias = mapped != null;
        }
        result.changed = !keyword.equals(raw);
        return result;
    }

    /**
     * 只裁首尾停用词。
     *
     * <p>规则刻意保守：裁完后至少保留 {@link #MIN_REMAIN} 个字符；
     * 停用词表按长度降序（快照服务已排序），先裁「我想听」再裁「的」。
     */
    private String trimStopwords(String keyword, List<String> stopwords) {
        String current = keyword;
        for (int round = 0; round < MAX_TRIM_ROUNDS; round++) {
            String before = current;
            for (String stopword : stopwords) {
                if (stopword == null || stopword.isEmpty() || current.length() <= stopword.length()) {
                    continue;
                }
                if (current.startsWith(stopword) && canTrim(current, stopword.length())) {
                    current = current.substring(stopword.length()).trim();
                }
                if (current.endsWith(stopword) && canTrim(current, stopword.length())) {
                    current = current.substring(0, current.length() - stopword.length()).trim();
                }
            }
            if (current.equals(before)) {
                break;
            }
        }
        return current;
    }

    private boolean canTrim(String current, int stopwordLength) {
        return current.length() - stopwordLength >= MIN_REMAIN;
    }

    private static String key(String word) {
        return word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
    }

    /** 一次改写的结果，供搜索服务选择「展示词 / 检索词」。 */
    public static final class RewriteResult {
        private String original;
        private String normalized;
        private String keyword;
        private String pinyinKeyword;
        private boolean traditionalConverted;
        private boolean stopwordTrimmed;
        private boolean synonymApplied;
        private boolean pinyinAlias;
        private boolean changed;

        public String getOriginal() {
            return original;
        }

        public String getNormalized() {
            return normalized;
        }

        public String getKeyword() {
            return keyword;
        }

        public String getPinyinKeyword() {
            return pinyinKeyword;
        }

        public boolean isTraditionalConverted() {
            return traditionalConverted;
        }

        public boolean isStopwordTrimmed() {
            return stopwordTrimmed;
        }

        public boolean isSynonymApplied() {
            return synonymApplied;
        }

        public boolean isPinyinAlias() {
            return pinyinAlias;
        }

        public boolean isChanged() {
            return changed;
        }
    }
}
