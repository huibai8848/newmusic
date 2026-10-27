package com.example.newmusic.module.recupgrade.rank;

import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import com.example.newmusic.module.recupgrade.model.Candidate;
import com.example.newmusic.module.recupgrade.model.RecTrace;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import com.example.newmusic.module.recupgrade.support.RecConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 重排算子链（FR-24-004 / P1）。
 *
 * <p>顺序即语义：过滤 → 去重 → 置顶 → 打散 → 加权 → 长尾 → 探索 → MMR → 裁剪。
 * 每一步都记录「输入多少、输出多少、改动了什么」，后台按 traceId 能还原
 * 「为什么这首排在第三位」（BR-24-044）。
 *
 * <p><b>置顶的实现偏差</b>：PRD 把 PIN 放在打散之前，但打散算法会移动置顶歌曲；
 * 因此这里在打散后再恢复一次置顶位置（trace 里两步都可见）。效果与「置顶不参与打散」
 * 一致，且不需要给每个打散算子加特例。
 */
@Slf4j
@Service
public class RerankService {

    private final RecConfigService configService;
    private final RecallDataService dataService;
    private final ExploreService exploreService;
    private final RecUpgradeProperties properties;

    public RerankService(RecConfigService configService, RecallDataService dataService,
                         ExploreService exploreService, RecUpgradeProperties properties) {
        this.configService = configService;
        this.dataService = dataService;
        this.exploreService = exploreService;
        this.properties = properties;
    }

    public List<Candidate> rerank(String scene, List<Candidate> input, RecallContext ctx, RecTrace trace) {
        long start = System.currentTimeMillis();
        List<Candidate> working = input == null ? new ArrayList<>() : new ArrayList<>(input);
        List<RecConfigService.OperatorSetting> operators = configService.rerank(scene);
        if (operators == null || operators.isEmpty()) {
            return truncate(working, properties.getFinalSize());
        }

        Map<Integer, Candidate> pins = new LinkedHashMap<>();
        List<Integer> pinPositions = new ArrayList<>();
        for (RecConfigService.OperatorSetting operator : operators) {
            String type = operator.getType() == null ? "" : operator.getType().toUpperCase(Locale.ROOT);
            int before = working.size();
            try {
                switch (type) {
                    case "FILTER_OFFLINE":
                        working = filterOffline(working);
                        break;
                    case "DEDUP":
                        working = dedup(working);
                        break;
                    case "PIN": {
                        JsonNode raw = operator.getRaw();
                        pinPositions = intList(raw.path("positions"));
                        int pinned = pin(working, pinPositions, raw.path("source").asText("editor"), pins);
                        step(trace, type, before, working.size(), "pinned=" + pinned);
                        continue;
                    }
                    case "DIVERSIFY_ARTIST":
                        working = diversifyByArtist(working, operator.getRaw().path("window").asInt(8),
                                operator.getRaw().path("maxCount").asInt(2));
                        break;
                    case "DIVERSIFY_ALBUM":
                        working = diversifyByKey(working, true,
                                operator.getRaw().path("maxCount").asInt(1));
                        break;
                    case "DIVERSIFY_TAG":
                        working = diversifyByKey(working, false,
                                Math.max(1, (int) Math.ceil(properties.getFinalSize()
                                        * operator.getRaw().path("maxRatio").asDouble(0.4))));
                        break;
                    case "BOOST_NEW":
                        working = boostNew(working, operator.getRaw().path("days").asInt(7),
                                operator.getRaw().path("boost").asDouble(1.15));
                        break;
                    case "LONGTAIL_INSERT":
                        working = insertLongTail(working, operator.getRaw().path("interval").asInt(10),
                                operator.getRaw().path("minRatio").asDouble(0.10));
                        break;
                    case "EXPLORE_INSERT": {
                        // 置顶先归位再插探索：否则 PIN_RESTORE 的 remove+add 会把探索位整体
                        // 左移，出现「探索位落在首屏第 6 位」这种违背 AC-24-123 的结果。
                        if (!pins.isEmpty()) {
                            restorePins(working, pins);
                            step(trace, "PIN_RESTORE_PRE_EXPLORE", working.size(), working.size(),
                                    "positions=" + pinPositions);
                        }
                        double ratio = operator.getRaw().path("ratio").asDouble(properties.getExploreRatio());
                        int inserted = insertExplore(working, ctx, ratio, pins);
                        step(trace, type, before, working.size(), "inserted=" + inserted);
                        continue;
                    }
                    case "MMR":
                        working = mmr(working, operator.getRaw().path("lambda").asDouble(0.7),
                                properties.getFinalSize());
                        break;
                    case "TRUNCATE":
                        working = truncate(working, operator.getRaw().path("size")
                                .asInt(properties.getFinalSize()));
                        break;
                    default:
                        step(trace, type, before, working.size(), "unknown operator, skipped");
                        continue;
                }
                step(trace, type, before, working.size(), "");
            } catch (RuntimeException e) {
                // EX-24-004-4：单个算子配置错误只跳过它，不能让整个推荐失败
                trace.getWarnings().add("OPERATOR_ERROR:" + type + ":" + e.getMessage());
                log.warn("重排算子执行失败 type={} cause={}", type, e.toString());
            }
        }
        if (!pins.isEmpty()) {
            restorePins(working, pins);
            step(trace, "PIN_RESTORE", working.size(), working.size(), "positions=" + pinPositions);
        }
        // 配置里没有 TRUNCATE 时也要给出最终条数，避免把 150 首全塞给客户端
        int finalSize = properties.getFinalSize();
        if (!containsOperator(operators, "TRUNCATE") && working.size() > finalSize) {
            working = truncate(working, finalSize);
            step(trace, "DEFAULT_TRUNCATE", working.size(), finalSize, "");
        }
        trace.timing("rerank", System.currentTimeMillis() - start);
        trace.setRerankCount(working.size());
        return working;
    }

    // ------------------------------------------------------------------ 算子实现

    private List<Candidate> filterOffline(List<Candidate> list) {
        Map<Long, Song> playable = dataService.playableMap();
        List<Candidate> result = new ArrayList<>(list.size());
        for (Candidate candidate : list) {
            if (candidate.getSongId() != null && playable.containsKey(candidate.getSongId())) {
                candidate.setSong(playable.get(candidate.getSongId()));
                result.add(candidate);
            }
        }
        return result;
    }

    private List<Candidate> dedup(List<Candidate> list) {
        Set<Long> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        List<Candidate> result = new ArrayList<>(list.size());
        for (Candidate candidate : list) {
            if (candidate.getSongId() == null || !ids.add(candidate.getSongId())) {
                continue;
            }
            Song song = candidate.getSong();
            if (song != null && song.getName() != null) {
                String key = (song.getName() + '|' + (song.getArtistName() == null ? "" : song.getArtistName()))
                        .trim().toLowerCase(Locale.ROOT);
                if (!names.add(key)) {
                    // 同曲不同版本（Live/Remix）只留分数最高的一首
                    continue;
                }
            }
            result.add(candidate);
        }
        return result;
    }

    private int pin(List<Candidate> working, List<Integer> positions, String source,
                    Map<Integer, Candidate> pins) {
        if (positions == null || positions.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Integer position : positions) {
            if (position == null || position <= 0) {
                continue;
            }
            Candidate picked = findUnpinned(working, pins, source);
            if (picked == null) {
                // BR-24-045 的例外：运营歌曲可以不在召回集内
                picked = fromEditorPool(working, pins);
            }
            if (picked == null) {
                // EX-24-004-2：置顶歌曲下架/不存在时跳过该位置
                continue;
            }
            working.remove(picked);
            int index = Math.min(position - 1, working.size());
            working.add(index, picked);
            pins.put(position, picked);
            count++;
        }
        return count;
    }

    private Candidate findUnpinned(List<Candidate> working, Map<Integer, Candidate> pins, String source) {
        Set<Long> pinnedIds = new HashSet<>();
        for (Candidate candidate : pins.values()) {
            pinnedIds.add(candidate.getSongId());
        }
        for (Candidate candidate : working) {
            if (pinnedIds.contains(candidate.getSongId())) {
                continue;
            }
            if ("editor".equalsIgnoreCase(source) && candidate.isEditor()) {
                return candidate;
            }
            if ("recall".equalsIgnoreCase(source)) {
                return candidate;
            }
        }
        return null;
    }

    private Candidate fromEditorPool(List<Candidate> working, Map<Integer, Candidate> pins) {
        Set<Long> existing = new HashSet<>();
        for (Candidate candidate : working) {
            existing.add(candidate.getSongId());
        }
        for (Candidate candidate : pins.values()) {
            existing.add(candidate.getSongId());
        }
        for (Long songId : dataService.editorSongs(50)) {
            if (existing.contains(songId)) {
                continue;
            }
            Song song = dataService.findPlayable(songId);
            if (song == null) {
                continue;
            }
            Candidate candidate = new Candidate();
            candidate.setSongId(songId);
            candidate.setSong(song);
            candidate.setEditor(true);
            candidate.setArtistId(song.getArtistId());
            candidate.setAlbumId(song.getAlbumId());
            candidate.setGenre(song.getGenre());
            candidate.setLanguage(song.getLanguage());
            candidate.setHotScore(song.getHotScore() == null ? 0 : song.getHotScore());
            candidate.setReleaseDays(dataService.releaseDays(song));
            candidate.setFineScore(1.0);
            candidate.setCoarseScore(1.0);
            candidate.setScoreMode("PIN");
            candidate.addSource("editor", 1.0);
            working.add(candidate);
            return candidate;
        }
        return null;
    }

    /** 滑动窗口打散：任意连续 window 首内同一歌手不超过 maxCount。 */
    private List<Candidate> diversifyByArtist(List<Candidate> ranked, int windowSize, int maxCount) {
        if (ranked.size() <= maxCount) {
            return new ArrayList<>(ranked);
        }
        List<Candidate> result = new ArrayList<>(ranked.size());
        Map<Long, Integer> windowCount = new HashMap<>();
        Deque<Candidate> window = new ArrayDeque<>();
        List<Candidate> pending = new ArrayList<>(ranked);
        int windowLimit = Math.max(1, windowSize);
        int limit = Math.max(1, maxCount);
        while (!pending.isEmpty()) {
            Candidate picked = null;
            for (Candidate candidate : pending) {
                Long artist = candidate.getArtistId();
                if (artist == null || windowCount.getOrDefault(artist, 0) < limit) {
                    picked = candidate;
                    break;
                }
            }
            if (picked == null) {
                // EX-24-004-1：无法满足时放宽，取当前最高分
                picked = pending.get(0);
            }
            pending.remove(picked);
            result.add(picked);
            window.addLast(picked);
            if (picked.getArtistId() != null) {
                windowCount.merge(picked.getArtistId(), 1, Integer::sum);
            }
            if (window.size() > windowLimit) {
                Candidate out = window.pollFirst();
                if (out != null && out.getArtistId() != null) {
                    windowCount.merge(out.getArtistId(), -1, Integer::sum);
                }
            }
        }
        return result;
    }

    /** 专辑/风格打散：按 key 计数，超限的候选后移；全部超限时放宽。 */
    private List<Candidate> diversifyByKey(List<Candidate> ranked, boolean album, int maxCount) {
        Map<String, Integer> count = new HashMap<>();
        List<Candidate> result = new ArrayList<>(ranked.size());
        List<Candidate> pending = new ArrayList<>(ranked);
        while (!pending.isEmpty()) {
            Candidate picked = null;
            for (Candidate candidate : pending) {
                String key = keyOf(candidate, album);
                if (key == null || count.getOrDefault(key, 0) < maxCount) {
                    picked = candidate;
                    break;
                }
            }
            if (picked == null) {
                picked = pending.get(0);
            }
            pending.remove(picked);
            result.add(picked);
            String key = keyOf(picked, album);
            if (key != null) {
                count.merge(key, 1, Integer::sum);
            }
        }
        return result;
    }

    private String keyOf(Candidate candidate, boolean album) {
        if (album) {
            return candidate.getAlbumId() == null ? null : String.valueOf(candidate.getAlbumId());
        }
        return candidate.getGenre() == null || candidate.getGenre().isEmpty() ? null : candidate.getGenre();
    }

    private List<Candidate> boostNew(List<Candidate> list, int days, double boost) {
        List<Candidate> result = new ArrayList<>(list);
        for (Candidate candidate : result) {
            if (candidate.isNewSong() && candidate.getReleaseDays() <= Math.max(1, days)) {
                candidate.setFineScore(Math.min(1.0, candidate.getFineScore() * boost));
            }
        }
        result.sort(Comparator.comparingDouble(Candidate::getFineScore).reversed()
                .thenComparing(Candidate::getSongId));
        return result;
    }

    /** 每 interval 首至少 1 首长尾，且最终列表长尾占比不低于 minRatio。 */
    private List<Candidate> insertLongTail(List<Candidate> list, int interval, double minRatio) {
        if (list.isEmpty()) {
            return list;
        }
        int firstN = Math.min(properties.getFinalSize(), list.size());
        int target = (int) Math.ceil(firstN * Math.max(0, minRatio));
        int current = 0;
        for (int i = 0; i < firstN; i++) {
            if (list.get(i).isLongTail()) {
                current++;
            }
        }
        if (current >= target) {
            return list;
        }
        List<Candidate> result = new ArrayList<>(list);
        List<Candidate> pool = new ArrayList<>();
        for (int i = firstN; i < result.size(); i++) {
            if (result.get(i).isLongTail()) {
                pool.add(result.get(i));
            }
        }
        int step = Math.max(1, interval);
        int need = target - current;
        for (int i = 0; i < need && !pool.isEmpty(); i++) {
            int pos = Math.min(firstN - 1, step * (i + 1) - 1);
            while (pos < firstN && result.get(pos).isLongTail() && pos + 1 < firstN) {
                pos++;
            }
            Candidate tail = pool.remove(0);
            result.remove(tail);
            result.add(Math.min(pos, result.size()), tail);
        }
        return result;
    }

    private int insertExplore(List<Candidate> list, RecallContext ctx, double ratio,
                              Map<Integer, Candidate> pins) {
        int firstN = Math.min(properties.getFinalSize(), list.size());
        List<Integer> positions = exploreService.positions(firstN, ratio);
        if (positions.isEmpty()) {
            return 0;
        }
        // 多取两个候选：置顶位冲突时会顺延到下一个可用位置，避免实际探索位少于计划
        List<Candidate> explore = exploreService.select(ctx, list, positions.size() + 2);
        if (explore.isEmpty()) {
            return 0;
        }
        // Candidate 是可变对象（Lombok 全字段 equals/hashCode），不能用普通 HashSet：
        // 打探索标记会改变 hashCode，导致 contains 失灵。这里按引用身份判断。
        Set<Candidate> pinned = Collections.newSetFromMap(new IdentityHashMap<>());
        pinned.addAll(pins.values());
        Set<Candidate> used = Collections.newSetFromMap(new IdentityHashMap<>());
        int inserted = 0;
        int cursor = 0;
        for (int position : positions) {
            // 目标位置被运营置顶占用时顺延，但不越过首屏下限（positions 本身从第 8 位起）
            int target = position - 1;
            while (target < firstN && target < list.size()
                    && list.get(target) != null && pinned.contains(list.get(target))) {
                target++;
            }
            if (target >= firstN || target >= list.size()) {
                continue;
            }
            Candidate candidate = null;
            while (cursor < explore.size()) {
                Candidate next = explore.get(cursor++);
                if (next != null && !pinned.contains(next) && !used.contains(next)
                        && containsIdentity(list, next)) {
                    candidate = next;
                    break;
                }
            }
            if (candidate == null) {
                break;
            }
            removeIdentity(list, candidate);
            list.add(Math.min(target, list.size()), candidate);
            used.add(candidate);
            candidate.setExplored(true);
            candidate.setExploreWeight(1.0);
            inserted++;
        }
        // select() 会先给所有候选打探索标记；没真正插入的要撤回，否则响应里会出现
        // 「标记为探索、位置却在首屏内」的假探索位（AC-24-123 会直接失真）
        for (Candidate candidate : explore) {
            if (candidate != null && !used.contains(candidate)) {
                candidate.setExplored(false);
            }
        }
        return inserted;
    }

    /** MMR：λ×相关性 − (1−λ)×最大相似度。相似度用歌手/风格/语言的粗粒度代理。 */
    private List<Candidate> mmr(List<Candidate> list, double lambda, int k) {
        if (list.size() <= 1) {
            return new ArrayList<>(list);
        }
        List<Candidate> pool = new ArrayList<>(list);
        List<Candidate> selected = new ArrayList<>(Math.min(k, list.size()));
        double maxScore = 0.0001;
        for (Candidate candidate : list) {
            maxScore = Math.max(maxScore, candidate.getFineScore());
        }
        int limit = Math.min(k, list.size());
        while (selected.size() < limit && !pool.isEmpty()) {
            Candidate best = null;
            double bestValue = -Double.MAX_VALUE;
            for (Candidate candidate : pool) {
                double similarity = 0;
                for (Candidate chosen : selected) {
                    similarity = Math.max(similarity, similarity(candidate, chosen));
                }
                double value = lambda * (candidate.getFineScore() / maxScore)
                        - (1 - lambda) * similarity;
                if (value > bestValue) {
                    bestValue = value;
                    best = candidate;
                }
            }
            if (best == null) {
                break;
            }
            selected.add(best);
            pool.remove(best);
        }
        selected.addAll(pool);
        return selected;
    }

    private double similarity(Candidate a, Candidate b) {
        double similarity = 0;
        if (a.getArtistId() != null && a.getArtistId().equals(b.getArtistId())) {
            similarity += 0.6;
        }
        if (a.getGenre() != null && a.getGenre().equals(b.getGenre())) {
            similarity += 0.3;
        }
        if (a.getLanguage() != null && a.getLanguage().equals(b.getLanguage())) {
            similarity += 0.1;
        }
        return Math.min(1.0, similarity);
    }

    private List<Candidate> truncate(List<Candidate> list, int size) {
        int limit = size <= 0 ? properties.getFinalSize() : size;
        return list.size() <= limit ? new ArrayList<>(list) : new ArrayList<>(list.subList(0, limit));
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 把置顶歌曲放回目标位置。
     *
     * <p>必须先全部摘出再按位置升序插回：逐个「移除→插入」时，后一个置顶歌曲如果
     * 原本排在前面，移除它会把已经放好的前一个置顶歌曲挤左一位（目标 8 实际落到 7）。
     * 这会连带把探索位顶出规划位置。
     */
    private void restorePins(List<Candidate> working, Map<Integer, Candidate> pins) {
        if (pins == null || pins.isEmpty()) {
            return;
        }
        List<Map.Entry<Integer, Candidate>> entries = new ArrayList<>();
        for (Map.Entry<Integer, Candidate> entry : pins.entrySet()) {
            if (entry.getValue() != null) {
                entries.add(entry);
            }
        }
        for (Map.Entry<Integer, Candidate> entry : entries) {
            removeIdentity(working, entry.getValue());
        }
        entries.sort(Comparator.comparingInt(Map.Entry::getKey));
        for (Map.Entry<Integer, Candidate> entry : entries) {
            int index = Math.min(Math.max(0, entry.getKey() - 1), working.size());
            working.add(index, entry.getValue());
        }
    }

    private boolean containsIdentity(List<Candidate> list, Candidate target) {
        for (Candidate candidate : list) {
            if (candidate == target) {
                return true;
            }
        }
        return false;
    }

    private void removeIdentity(List<Candidate> list, Candidate target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) {
                list.remove(i);
                return;
            }
        }
    }

    private List<Integer> intList(JsonNode node) {
        List<Integer> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                result.add(item.asInt());
            }
        }
        return result;
    }

    private boolean containsOperator(List<RecConfigService.OperatorSetting> operators, String type) {
        for (RecConfigService.OperatorSetting operator : operators) {
            if (type.equalsIgnoreCase(operator.getType())) {
                return true;
            }
        }
        return false;
    }

    private void step(RecTrace trace, String operator, int before, int after, String note) {
        if (trace == null) {
            return;
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("operator", operator);
        step.put("before", before);
        step.put("after", after);
        if (note != null && !note.isEmpty()) {
            step.put("note", note);
        }
        trace.getRerankSteps().add(step);
    }
}
