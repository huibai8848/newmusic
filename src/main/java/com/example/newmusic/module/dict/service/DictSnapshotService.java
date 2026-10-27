package com.example.newmusic.module.dict.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.newmusic.common.util.PinyinUtil;
import com.example.newmusic.module.dict.config.DictProperties;
import com.example.newmusic.module.dict.entity.IkDictWord;
import com.example.newmusic.module.dict.entity.IkStopword;
import com.example.newmusic.module.dict.entity.PinyinDict;
import com.example.newmusic.module.dict.entity.SynonymWord;
import com.example.newmusic.module.dict.mapper.IkDictWordMapper;
import com.example.newmusic.module.dict.mapper.IkStopwordMapper;
import com.example.newmusic.module.dict.mapper.PinyinDictMapper;
import com.example.newmusic.module.dict.mapper.SynonymMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 词典快照（模块 11，FR-20-001）。
 *
 * <p>ES 的 IK 远程词典每 60 秒来拉一次（BR-20-009），后台每次改词都会让全量查询重新扫表。
 * 这里做 30 秒快照缓存（PRD 伪代码的口径），并用内容 SHA-256 作为 ETag：
 * 即使两分钟内没人改词，ES 拿到的也永远是同一个版本号，可以安全地 304。
 *
 * <p>快照是**只读派生数据**，失效由写服务显式调用 {@link #invalidate()} 完成；
 * 不做「查 update_time 判断是否过期」的额外查询——那等于每次请求都多打一次库，
 * 而写操作的次数远小于读。
 */
@Slf4j
@Service
public class DictSnapshotService {

    public static final String TYPE_MAIN = "main";
    public static final String TYPE_STOPWORD = "stopword";
    public static final String TYPE_SYNONYM = "synonym";
    public static final String TYPE_PINYIN = "pinyin";

    private static final Set<String> TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<>(java.util.Arrays.asList(TYPE_MAIN, TYPE_STOPWORD, TYPE_SYNONYM, TYPE_PINYIN)));

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final IkDictWordMapper wordMapper;
    private final IkStopwordMapper stopwordMapper;
    private final SynonymMapper synonymMapper;
    private final PinyinDictMapper pinyinMapper;
    private final DictProperties properties;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    /** ES 最近一次拉取（200，不算 304）的时间；只在内存里，重启清零是合理的 */
    private final Map<String, LocalDateTime> lastPulls = new ConcurrentHashMap<>();
    private volatile RulesCached rules;
    private volatile long pinyinOverridesBuiltAt = 0L;

    public DictSnapshotService(IkDictWordMapper wordMapper, IkStopwordMapper stopwordMapper,
                               SynonymMapper synonymMapper, PinyinDictMapper pinyinMapper,
                               DictProperties properties) {
        this.wordMapper = wordMapper;
        this.stopwordMapper = stopwordMapper;
        this.synonymMapper = synonymMapper;
        this.pinyinMapper = pinyinMapper;
        this.properties = properties;
    }

    public static boolean isValidType(String type) {
        return type != null && TYPES.contains(type);
    }

    public static Set<String> types() {
        return TYPES;
    }

    /** 取某一类词典的文本快照；非法 type 由调用方转成 10001。 */
    public Snapshot get(String type) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(type);
        if (cached != null && now - cached.builtAtMillis < properties.getSnapshotCacheSeconds() * 1000L) {
            return cached.snapshot;
        }
        synchronized (this) {
            cached = cache.get(type);
            if (cached != null && now - cached.builtAtMillis < properties.getSnapshotCacheSeconds() * 1000L) {
                return cached.snapshot;
            }
            Snapshot snapshot = build(type);
            cache.put(type, new Cached(snapshot, now));
            return snapshot;
        }
    }

    /**
     * 查询改写所需的规则（停用词、同义词、拼音多音字）。
     *
     * <p>与 {@link #get(String)} 共享 30 秒缓存窗口；任何写操作都会同时失效两者。
     */
    public QueryRules queryRules() {
        long now = System.currentTimeMillis();
        RulesCached cached = rules;
        if (cached != null && now - cached.builtAtMillis < properties.getSnapshotCacheSeconds() * 1000L) {
            return cached.rules;
        }
        synchronized (this) {
            cached = rules;
            if (cached != null && now - cached.builtAtMillis < properties.getSnapshotCacheSeconds() * 1000L) {
                return cached.rules;
            }
            QueryRules built = buildQueryRules();
            rules = new RulesCached(built, now);
            return built;
        }
    }

    /** 写操作后调用；下一次读会重建。 */
    public void invalidate() {
        cache.clear();
        rules = null;
        pinyinOverridesBuiltAt = 0L;
    }

    /** 远程词典每次真正返回 200 时记一笔，供后台展示「ES 上次拉取时间」。 */
    public void markPulled(String type) {
        if (isValidType(type)) {
            lastPulls.put(type, LocalDateTime.now());
        }
    }

    public LocalDateTime lastPull(String type) {
        return lastPulls.get(type);
    }

    /**
     * 把多音字词典灌进 {@link PinyinUtil}。
     *
     * <p>说明：这只影响**之后新写入**的拼音列；已存在的行由
     * {@code PinyinDictRefresher} 在启动/词典变更时定向重算。
     */
    public void applyPinyinOverrides() {
        long now = System.currentTimeMillis();
        if (now - pinyinOverridesBuiltAt < properties.getSnapshotCacheSeconds() * 1000L) {
            return;
        }
        Map<String, String> overrides = new LinkedHashMap<>();
        for (PinyinDict row : enabledPinyin()) {
            overrides.put(row.getWord(), row.getPinyin());
        }
        PinyinUtil.setWordOverrides(overrides);
        pinyinOverridesBuiltAt = now;
    }

    // ==================== 构建 ====================

    private Snapshot build(String type) {
        switch (type) {
            case TYPE_MAIN:
                return buildMain();
            case TYPE_STOPWORD:
                return buildStopword();
            case TYPE_SYNONYM:
                return buildSynonym();
            case TYPE_PINYIN:
                return buildPinyin();
            default:
                throw new IllegalArgumentException("unknown dict type: " + type);
        }
    }

    private Snapshot buildMain() {
        List<IkDictWord> rows = wordMapper.selectList(new LambdaQueryWrapper<IkDictWord>()
                .eq(IkDictWord::getEnabled, 1)
                .orderByDesc(IkDictWord::getFrequency)
                .orderByAsc(IkDictWord::getWord));
        StringBuilder sb = new StringBuilder(rows.size() * 16);
        Set<String> words = new HashSet<>(Math.max(16, rows.size() * 2));
        LocalDateTime latest = null;
        for (IkDictWord row : rows) {
            sb.append(row.getWord());
            if (row.getFrequency() != null) {
                sb.append(' ').append(row.getFrequency());
            }
            if (row.getPos() != null && !row.getPos().isEmpty()) {
                sb.append(' ').append(row.getPos());
            }
            sb.append('\n');
            words.add(row.getWord());
            latest = later(latest, row.getUpdateTime());
        }
        Snapshot snapshot = snapshot(TYPE_MAIN, sb.toString(), rows.size(), latest);
        snapshot.mainWords = words;
        return snapshot;
    }

    private Snapshot buildStopword() {
        List<IkStopword> rows = stopwordMapper.selectList(new LambdaQueryWrapper<IkStopword>()
                .eq(IkStopword::getEnabled, 1)
                .orderByAsc(IkStopword::getWord));
        StringBuilder sb = new StringBuilder(rows.size() * 8);
        List<String> words = new ArrayList<>(rows.size());
        LocalDateTime latest = null;
        for (IkStopword row : rows) {
            sb.append(row.getWord()).append('\n');
            words.add(row.getWord());
            latest = later(latest, row.getUpdateTime());
        }
        Snapshot snapshot = snapshot(TYPE_STOPWORD, sb.toString(), rows.size(), latest);
        snapshot.stopwords = words;
        return snapshot;
    }

    private Snapshot buildSynonym() {
        List<SynonymWord> rows = synonymMapper.selectList(new LambdaQueryWrapper<SynonymWord>()
                .eq(SynonymWord::getEnabled, 1)
                .orderByAsc(SynonymWord::getGroupNo)
                .orderByAsc(SynonymWord::getSortOrder)
                .orderByAsc(SynonymWord::getId));
        Map<Integer, List<SynonymWord>> groups = group(rows);
        StringBuilder sb = new StringBuilder(rows.size() * 16);
        LocalDateTime latest = null;
        for (Map.Entry<Integer, List<SynonymWord>> entry : groups.entrySet()) {
            List<SynonymWord> group = entry.getValue();
            SynonymWord primary = primaryOf(group);
            if (primary == null) {
                continue;
            }
            String type = primary.getSynonymType();
            if ("ONE_WAY".equalsIgnoreCase(type)) {
                List<String> targets = new ArrayList<>();
                for (SynonymWord word : group) {
                    if (!word.getId().equals(primary.getId())) {
                        targets.add(word.getWord());
                    }
                }
                if (targets.isEmpty()) {
                    continue;
                }
                sb.append(primary.getWord()).append(" => ").append(String.join(",", targets)).append('\n');
            } else {
                List<String> words = new ArrayList<>();
                // 主词放最前（PRD 的可读性要求）
                words.add(primary.getWord());
                for (SynonymWord word : group) {
                    if (!word.getId().equals(primary.getId())) {
                        words.add(word.getWord());
                    }
                }
                sb.append(String.join(",", words)).append('\n');
            }
            for (SynonymWord word : group) {
                latest = later(latest, word.getUpdateTime());
            }
        }
        return snapshot(TYPE_SYNONYM, sb.toString(), groups.size(), latest);
    }

    private Snapshot buildPinyin() {
        List<PinyinDict> rows = enabledPinyin();
        StringBuilder sb = new StringBuilder(rows.size() * 16);
        LocalDateTime latest = null;
        for (PinyinDict row : rows) {
            sb.append(row.getWord()).append(' ').append(row.getPinyin()).append('\n');
            latest = later(latest, row.getUpdateTime());
        }
        return snapshot(TYPE_PINYIN, sb.toString(), rows.size(), latest);
    }

    private QueryRules buildQueryRules() {
        QueryRules result = new QueryRules();

        List<IkStopword> stopwords = stopwordMapper.selectList(new LambdaQueryWrapper<IkStopword>()
                .eq(IkStopword::getEnabled, 1));
        for (IkStopword row : stopwords) {
            if (row.getWord() != null && !row.getWord().isEmpty()) {
                result.stopwords.add(row.getWord());
            }
        }
        result.stopwords.sort(Comparator.comparingInt(String::length).reversed()
                .thenComparing(Comparator.naturalOrder()));

        List<SynonymWord> synonyms = synonymMapper.selectList(new LambdaQueryWrapper<SynonymWord>()
                .eq(SynonymWord::getEnabled, 1)
                .orderByAsc(SynonymWord::getGroupNo)
                .orderByAsc(SynonymWord::getSortOrder)
                .orderByAsc(SynonymWord::getId));
        for (List<SynonymWord> group : group(synonyms).values()) {
            SynonymWord primary = primaryOf(group);
            if (primary == null || !"EQUIVALENT".equalsIgnoreCase(primary.getSynonymType())) {
                // 单向组在 MySQL LIKE 兜底里无法表达「A 也匹配 B」；
                // ES 侧由远程同义词文件处理。这里不改变查询词，避免丢掉原词的结果。
                continue;
            }
            for (SynonymWord word : group) {
                result.synonyms.put(normalizeKey(word.getWord()), primary.getWord());
            }
        }

        for (PinyinDict row : enabledPinyin()) {
            result.pinyin.put(row.getWord(), row.getPinyin());
            String raw = PinyinUtil.rawFullPinyin(row.getWord());
            if (raw != null && !raw.isEmpty() && !raw.equals(row.getPinyin())) {
                // 旧数据里「重庆」的拼音列是 zhongqing；用户敲词典里的 chongqing 时
                // 改成 zhongqing 去匹配存量列。新数据会在写入期直接用 chongqing。
                result.pinyinAliases.put(row.getPinyin(), raw);
            }
        }
        return result;
    }

    private List<PinyinDict> enabledPinyin() {
        return pinyinMapper.selectList(new LambdaQueryWrapper<PinyinDict>()
                .eq(PinyinDict::getEnabled, 1)
                .orderByAsc(PinyinDict::getWord));
    }

    private Snapshot snapshot(String type, String content, int count, LocalDateTime latest) {
        String version = sha256(content);
        Instant lastModified = latest == null ? Instant.EPOCH
                : latest.toInstant(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return new Snapshot(type, content, version, lastModified, count);
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然支持 SHA-256；真缺失时用内容 hashCode 兜底，但仍保证是稳定字符串
            return Integer.toHexString(content.hashCode());
        }
    }

    private static Map<Integer, List<SynonymWord>> group(List<SynonymWord> rows) {
        Map<Integer, List<SynonymWord>> groups = new LinkedHashMap<>();
        for (SynonymWord row : rows) {
            groups.computeIfAbsent(row.getGroupNo(), k -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    private static SynonymWord primaryOf(List<SynonymWord> group) {
        for (SynonymWord word : group) {
            if (word.getIsPrimary() != null && word.getIsPrimary() == 1) {
                return word;
            }
        }
        return group.isEmpty() ? null : group.get(0);
    }

    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    private static String normalizeKey(String word) {
        return word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
    }

    // ==================== 数据载体 ====================

    /** 远程词典响应所需的一切。 */
    public static final class Snapshot {
        private final String type;
        private final String content;
        private final String version;
        private final Instant lastModified;
        private final int count;
        private Set<String> mainWords = Collections.emptySet();
        private List<String> stopwords = Collections.emptyList();

        Snapshot(String type, String content, String version, Instant lastModified, int count) {
            this.type = type;
            this.content = content;
            this.version = version;
            this.lastModified = lastModified;
            this.count = count;
        }

        public String getType() {
            return type;
        }

        public String getContent() {
            return content;
        }

        public String getVersion() {
            return version;
        }

        public String getLastModifiedHeader() {
            return HTTP_DATE.format(lastModified);
        }

        public Instant getLastModified() {
            return lastModified;
        }

        public int getCount() {
            return count;
        }

        public Set<String> getMainWords() {
            return mainWords;
        }

        public List<String> getStopwords() {
            return stopwords;
        }
    }

    /** 查询改写规则。 */
    public static final class QueryRules {
        private final List<String> stopwords = new ArrayList<>();
        private final Map<String, String> synonyms = new HashMap<>();
        private final Map<String, String> pinyin = new LinkedHashMap<>();
        private final Map<String, String> pinyinAliases = new HashMap<>();

        public List<String> getStopwords() {
            return stopwords;
        }

        public Map<String, String> getSynonyms() {
            return synonyms;
        }

        public Map<String, String> getPinyin() {
            return pinyin;
        }

        public Map<String, String> getPinyinAliases() {
            return pinyinAliases;
        }
    }

    private static final class Cached {
        private final Snapshot snapshot;
        private final long builtAtMillis;

        Cached(Snapshot snapshot, long builtAtMillis) {
            this.snapshot = snapshot;
            this.builtAtMillis = builtAtMillis;
        }
    }

    private static final class RulesCached {
        private final QueryRules rules;
        private final long builtAtMillis;

        RulesCached(QueryRules rules, long builtAtMillis) {
            this.rules = rules;
            this.builtAtMillis = builtAtMillis;
        }
    }
}
