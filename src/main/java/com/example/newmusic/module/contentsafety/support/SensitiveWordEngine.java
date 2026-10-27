package com.example.newmusic.module.contentsafety.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感词匹配引擎：DFA（AC 自动机）+ 白名单 + 正则规则。
 *
 * <p>热更新采用「构建新快照 → volatile 原子替换」的双 buffer 方式（BR-27-004）：
 * 匹配线程永远读到一个完整快照，不会看到半棵 Trie。
 *
 * <p>日志里只出现脱敏词（BR-27-008），原始词条只可能在超级管理员显式查询时返回。
 */
@Slf4j
@Component
public class SensitiveWordEngine {

    private static final AtomicLong FALLBACK_ID = new AtomicLong(-1);

    private volatile Snapshot snapshot = Snapshot.fallback();

    /** 构建失败时保留旧快照（EX-27-001-2），返回 false 由调用方告警。 */
    public synchronized boolean reload(Collection<WordInput> words, Collection<String> whiteWords,
                                       Collection<RuleInput> rules, int version) {
        try {
            List<AcAutomaton.Word> sensitive = new ArrayList<>();
            for (WordInput input : words) {
                if (input == null || input.getWord() == null || input.getWord().trim().isEmpty()) {
                    continue;
                }
                String normalized = TextNormalizer.normalizeText(input.getWord());
                if (normalized.isEmpty()) {
                    continue;
                }
                sensitive.add(new AcAutomaton.Word(input.getId(), normalized, input.getWord(),
                        input.getLevel(), input.getCategory(), input.getSource()));
            }
            List<AcAutomaton.Word> whites = new ArrayList<>();
            for (String white : whiteWords) {
                if (white == null || white.trim().isEmpty()) {
                    continue;
                }
                String normalized = TextNormalizer.normalizeText(white);
                if (!normalized.isEmpty()) {
                    whites.add(new AcAutomaton.Word(-1, normalized, white, 0, "WHITE", "WHITE"));
                }
            }
            List<CompiledRule> compiled = new ArrayList<>();
            for (RuleInput rule : rules) {
                if (rule == null || rule.getPattern() == null || rule.getPattern().isEmpty()) {
                    continue;
                }
                try {
                    compiled.add(new CompiledRule(rule.getId(), rule.getRuleName(), rule.getAction(),
                            rule.getScene(), Pattern.compile(rule.getPattern())));
                } catch (Exception e) {
                    log.warn("审核规则编译失败，已跳过 ruleId={} name={}", rule.getId(), rule.getRuleName());
                }
            }
            Snapshot next = new Snapshot(AcAutomaton.build(sensitive), AcAutomaton.build(whites),
                    compiled, version, sensitive.size(), false);
            this.snapshot = next;
            log.info("敏感词库热加载完成 version={} words={} rules={}", version, sensitive.size(), compiled.size());
            return true;
        } catch (Throwable e) {
            log.error("敏感词库热加载失败，保留旧词库 version={} cause={}", version, e.toString());
            return false;
        }
    }

    public int version() {
        return snapshot.version;
    }

    public int wordCount() {
        return snapshot.wordCount;
    }

    public boolean isFallback() {
        return snapshot.fallback;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** 只做敏感词匹配；规则见 {@link #detect(String, String)}。 */
    public Result detect(String text) {
        return detect(text, "ALL");
    }

    public Result detect(String text, String scene) {
        Snapshot current = snapshot;
        TextNormalizer.Result normalized = TextNormalizer.normalize(text);
        List<AcAutomaton.Hit> whiteHits = current.whites.findAll(normalized.getText());
        List<AcAutomaton.Hit> wordHits = current.sensitive.findAll(normalized.getText());

        List<Match> matches = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int maxLevel = 0;
        for (AcAutomaton.Hit hit : wordHits) {
            if (insideWhite(hit.start, hit.end, whiteHits)) {
                continue;
            }
            String key = hit.word.getId() + ":" + hit.start + ":" + hit.end;
            if (!seen.add(key)) {
                continue;
            }
            matches.add(new Match(hit.word.getId(), mask(hit.word.getDisplay()), hit.word.getDisplay(),
                    hit.word.getLevel(), hit.word.getCategory(), hit.word.getSource(),
                    normalized.originalStart(hit.start), normalized.originalEnd(hit.end)));
            maxLevel = Math.max(maxLevel, hit.word.getLevel());
        }

        List<RuleMatch> ruleMatches = new ArrayList<>();
        for (CompiledRule rule : current.rules) {
            // 调用方 scene=ALL 表示「不限场景」，此时所有规则都参与；
            // 只有明确指定了场景才按规则场景过滤，避免通用探测漏掉场景专属规则。
            if (scene != null && !"ALL".equalsIgnoreCase(scene)
                    && !"ALL".equalsIgnoreCase(rule.scene)
                    && !rule.scene.equalsIgnoreCase(scene)) {
                continue;
            }
            addRuleMatches(rule, text, normalized, ruleMatches);
            if (!normalized.getText().equals(text == null ? "" : text)) {
                // 归一化文本上再跑一次，专门抓「1-3-8...」这类分隔符绕过。
                addRuleMatches(rule, null, normalized, ruleMatches);
            }
        }
        dedupeRules(ruleMatches);
        return new Result(matches, ruleMatches, maxLevel, current.version, current.fallback,
                normalized.getText());
    }

    private void addRuleMatches(CompiledRule rule, String raw,
                                TextNormalizer.Result normalized, List<RuleMatch> target) {
        String source = raw != null ? raw : normalized.getText();
        if (source == null || source.isEmpty()) {
            return;
        }
        Matcher matcher = rule.pattern.matcher(source);
        int from = 0;
        while (matcher.find(from)) {
            int start;
            int end;
            if (raw != null) {
                start = matcher.start();
                end = matcher.end();
            } else {
                start = normalized.originalStart(matcher.start());
                end = normalized.originalEnd(matcher.end());
            }
            target.add(new RuleMatch(rule.id, rule.ruleName, rule.action, start, end,
                    truncate(matcher.group(), 40)));
            from = matcher.end() > matcher.start() ? matcher.end() : matcher.start() + 1;
            if (target.size() > 200) {
                return;
            }
        }
    }

    private static void dedupeRules(List<RuleMatch> matches) {
        Set<String> seen = new LinkedHashSet<>();
        matches.removeIf(m -> !seen.add(m.getRuleId() + ":" + m.getStart() + ":" + m.getEnd()));
    }

    private static boolean insideWhite(int start, int end, List<AcAutomaton.Hit> whites) {
        for (AcAutomaton.Hit white : whites) {
            if (white.start <= start && white.end >= end) {
                return true;
            }
        }
        return false;
    }

    public static String mask(String word) {
        if (word == null || word.isEmpty()) {
            return "";
        }
        int length = word.codePointCount(0, word.length());
        if (length <= 1) {
            return "*";
        }
        if (length == 2) {
            return word.substring(0, 1) + "*";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(word, 0, 1);
        for (int i = 0; i < length - 2; i++) {
            sb.append('*');
        }
        sb.append(word, word.offsetByCodePoints(0, length - 1), word.length());
        return sb.toString();
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    /** 词条输入：由词库服务从实体映射，引擎不依赖 MyBatis。 */
    public static final class WordInput {
        private final long id;
        private final String word;
        private final int level;
        private final String category;
        private final String source;

        public WordInput(long id, String word, int level, String category, String source) {
            this.id = id;
            this.word = word;
            this.level = level;
            this.category = category;
            this.source = source;
        }

        public long getId() {
            return id;
        }

        public String getWord() {
            return word;
        }

        public int getLevel() {
            return level;
        }

        public String getCategory() {
            return category;
        }

        public String getSource() {
            return source;
        }
    }

    public static final class RuleInput {
        private final long id;
        private final String ruleName;
        private final String pattern;
        private final String scene;
        private final String action;

        public RuleInput(long id, String ruleName, String pattern, String scene, String action) {
            this.id = id;
            this.ruleName = ruleName;
            this.pattern = pattern;
            this.scene = scene;
            this.action = action;
        }

        public long getId() {
            return id;
        }

        public String getRuleName() {
            return ruleName;
        }

        public String getPattern() {
            return pattern;
        }

        public String getScene() {
            return scene;
        }

        public String getAction() {
            return action;
        }
    }

    public static final class Result {
        private final List<Match> matches;
        private final List<RuleMatch> ruleMatches;
        private final int maxLevel;
        private final int wordVersion;
        private final boolean fallback;
        private final String normalizedText;

        Result(List<Match> matches, List<RuleMatch> ruleMatches, int maxLevel,
               int wordVersion, boolean fallback, String normalizedText) {
            this.matches = matches;
            this.ruleMatches = ruleMatches;
            this.maxLevel = maxLevel;
            this.wordVersion = wordVersion;
            this.fallback = fallback;
            this.normalizedText = normalizedText;
        }

        public List<Match> getMatches() {
            return matches;
        }

        public List<RuleMatch> getRuleMatches() {
            return ruleMatches;
        }

        public int getMaxLevel() {
            return maxLevel;
        }

        public int getWordVersion() {
            return wordVersion;
        }

        public boolean isFallback() {
            return fallback;
        }

        public String getNormalizedText() {
            return normalizedText;
        }

        public boolean isEmpty() {
            return matches.isEmpty() && ruleMatches.isEmpty();
        }

        public boolean hasRejectRule() {
            for (RuleMatch rule : ruleMatches) {
                if ("REJECT".equalsIgnoreCase(rule.getAction())) {
                    return true;
                }
            }
            return false;
        }

        public boolean hasReviewRule() {
            for (RuleMatch rule : ruleMatches) {
                if ("REVIEW".equalsIgnoreCase(rule.getAction())) {
                    return true;
                }
            }
            return false;
        }

        public List<String> maskedWords() {
            List<String> words = new ArrayList<>();
            for (Match match : matches) {
                if (!words.contains(match.getWord())) {
                    words.add(match.getWord());
                }
            }
            return words;
        }
    }

    public static final class Match {
        private final long wordId;
        private final String word;
        /** 原始词条，只允许在运营侧（超管测试工具）使用，禁止直接返回给 C 端。 */
        private final String rawWord;
        private final int level;
        private final String category;
        private final String source;
        private final int start;
        private final int end;

        Match(long wordId, String word, String rawWord, int level, String category, String source,
              int start, int end) {
            this.wordId = wordId;
            this.word = word;
            this.rawWord = rawWord;
            this.level = level;
            this.category = category;
            this.source = source;
            this.start = start;
            this.end = end;
        }

        public long getWordId() {
            return wordId;
        }

        public String getWord() {
            return word;
        }

        public String getRawWord() {
            return rawWord;
        }

        public int getLevel() {
            return level;
        }

        public String getCategory() {
            return category;
        }

        public String getSource() {
            return source;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }
    }

    public static final class RuleMatch {
        private final long ruleId;
        private final String ruleName;
        private final String action;
        private final int start;
        private final int end;
        private final String sample;

        RuleMatch(long ruleId, String ruleName, String action, int start, int end, String sample) {
            this.ruleId = ruleId;
            this.ruleName = ruleName;
            this.action = action;
            this.start = start;
            this.end = end;
            this.sample = sample;
        }

        public long getRuleId() {
            return ruleId;
        }

        public String getRuleName() {
            return ruleName;
        }

        public String getAction() {
            return action;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        public String getSample() {
            return sample;
        }
    }

    private static final class CompiledRule {
        private final long id;
        private final String ruleName;
        private final String action;
        private final String scene;
        private final Pattern pattern;

        CompiledRule(long id, String ruleName, String action, String scene, Pattern pattern) {
            this.id = id;
            this.ruleName = ruleName;
            this.action = action == null ? "REVIEW" : action.toUpperCase(Locale.ROOT);
            this.scene = scene == null ? "ALL" : scene;
            this.pattern = pattern;
        }
    }

    /** 不可变快照；volatile 替换实现双 buffer。 */
    public static final class Snapshot {
        private final AcAutomaton sensitive;
        private final AcAutomaton whites;
        private final List<CompiledRule> rules;
        private final int version;
        private final int wordCount;
        private final boolean fallback;

        Snapshot(AcAutomaton sensitive, AcAutomaton whites, List<CompiledRule> rules,
                 int version, int wordCount, boolean fallback) {
            this.sensitive = sensitive;
            this.whites = whites;
            this.rules = Collections.unmodifiableList(rules);
            this.version = version;
            this.wordCount = wordCount;
            this.fallback = fallback;
        }

        static Snapshot fallback() {
            List<AcAutomaton.Word> words = new ArrayList<>();
            for (String word : new String[]{"敏感词", "赌博网站", "违禁品交易"}) {
                words.add(new AcAutomaton.Word(FALLBACK_ID.getAndDecrement(),
                        TextNormalizer.normalizeText(word), word, 3, "CUSTOM", "BUILTIN"));
            }
            return new Snapshot(AcAutomaton.build(words), AcAutomaton.build(Collections.emptyList()),
                    Collections.emptyList(), 0, words.size(), true);
        }
    }
}
