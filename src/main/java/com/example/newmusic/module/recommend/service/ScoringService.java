package com.example.newmusic.module.recommend.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.play.entity.PlayHistory;
import com.example.newmusic.module.play.service.PlayHistoryService;
import com.example.newmusic.module.recommend.config.RecommendProperties;
import com.example.newmusic.module.recommend.entity.UserPreference;
import com.example.newmusic.module.recommend.mapper.UserPreferenceMapper;
import com.example.newmusic.module.recommend.vo.RecommendItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可解释的推荐打分。
 *
 * <p>本机没有 Milvus / 向量服务，PRD 里的「向量召回 + 精排模型」无法按原样实现。
 * 这里用**行为聚合 + 内容特征**作为务实替代：把用户最近 90 天的播放按时间衰减
 * 汇总成风格/语种/歌手/标签四张权重表，再对候选歌曲做加权打分。
 * 它的优点是每一分都能说清来源，推荐理由天然真实（BR-17-013）。
 *
 * <p>打分结果只用于排序，不做 CTR 预估；接口契约与未来接入模型时一致。
 */
@Slf4j
@Service
public class ScoringService {

    private final PlayHistoryService playHistoryService;
    private final CandidateService candidateService;
    private final UserPreferenceMapper preferenceMapper;
    private final FeedbackService feedbackService;
    private final RecommendProperties properties;

    public ScoringService(PlayHistoryService playHistoryService, CandidateService candidateService,
                          UserPreferenceMapper preferenceMapper, FeedbackService feedbackService,
                          RecommendProperties properties) {
        this.playHistoryService = playHistoryService;
        this.candidateService = candidateService;
        this.preferenceMapper = preferenceMapper;
        this.feedbackService = feedbackService;
        this.properties = properties;
    }

    /** 构建用户画像。调用一次后可对整批候选打分。 */
    public TasteProfile buildProfile(Long userId) {
        TasteProfile profile = new TasteProfile();
        profile.userId = userId;
        profile.behaviorCount = playHistoryService.behaviorCount(userId);
        profile.coldStart = profile.behaviorCount < properties.getColdStartBehaviorThreshold();

        // BR-17-047：问卷权重每 10 条真实行为衰减 20%，保底 0.2
        double preferenceWeight = Math.max(0.2, 1.0 - 0.2 * Math.floor(profile.behaviorCount / 10.0));
        UserPreference preference = preferenceMapper.selectOne(
                Wrappers.<UserPreference>lambdaQuery()
                        .eq(UserPreference::getUserId, userId)
                        .orderByDesc(UserPreference::getUpdateTime)
                        .last("LIMIT 1"));
        if (preference != null) {
            for (String style : split(preference.getStyles())) {
                profile.genreWeights.merge(style, preferenceWeight, Double::sum);
            }
            for (String language : split(preference.getLanguages())) {
                profile.languageWeights.merge(language, preferenceWeight, Double::sum);
            }
            for (String artist : split(preference.getArtists())) {
                try {
                    profile.artistWeights.merge(Long.valueOf(artist), preferenceWeight * 1.2, Double::sum);
                } catch (NumberFormatException ignored) {
                    // 脏数据
                }
            }
            profile.mood = preference.getMood();
        }

        if (userId != null) {
            List<PlayHistory> rows = playHistoryService.recentHistory(userId, 90, properties.getRecentPlayLimit());
            Map<Long, Set<Long>> tags = candidateService.songTags();
            LocalDateTime now = LocalDateTime.now();
            for (PlayHistory row : rows) {
                if (row.getSongId() == null) {
                    continue;
                }
                profile.listenedSongIds.add(row.getSongId());
                long days = row.getPlayTime() == null ? 0
                        : Math.max(0, Math.min(90, ChronoUnit.DAYS.between(row.getPlayTime(), now)));
                // 完整播放 1.0、未达有效播放 0.35；λ = 0.05（约 14 天衰减一半）
                double decay = Math.exp(-0.05 * days) * (Integer.valueOf(1).equals(row.getIsValid()) ? 1.0 : 0.35);
                if (row.getArtistId() != null) {
                    profile.artistWeights.merge(row.getArtistId(), decay, Double::sum);
                }
                if (row.getGenre() != null && !row.getGenre().isEmpty()) {
                    profile.genreWeights.merge(row.getGenre(), decay * 0.6, Double::sum);
                }
                if (row.getLanguage() != null && !row.getLanguage().isEmpty()) {
                    profile.languageWeights.merge(row.getLanguage(), decay * 0.4, Double::sum);
                }
                for (Long tagId : tags.getOrDefault(row.getSongId(), Collections.<Long>emptySet())) {
                    profile.tagWeights.merge(tagId, decay * 0.4, Double::sum);
                }
            }
            profile.recentSongIds.addAll(playHistoryService.recentSongIds(userId, 50));
            profile.likedSongIds.addAll(feedbackService.likedSongIds(userId));
        }

        normalize(profile.artistWeights);
        normalize(profile.genreWeights);
        normalize(profile.languageWeights);
        normalize(profile.tagWeights);
        return profile;
    }

    /**
     * 对一首歌打分。返回值里的 reason 由贡献最大的那一项决定，因此理由与排序依据一致。
     */
    public ScoredSong score(Song song, TasteProfile profile) {
        Map<Long, Set<Long>> tags = candidateService.songTags();
        double hot = clamp((song.getHotScore() == null ? 0 : song.getHotScore()) / 1000.0);
        double genre = genreWeight(profile, song.getGenre());
        double language = profile.languageWeights.getOrDefault(song.getLanguage(), 0.0);
        double artist = profile.artistWeights.getOrDefault(song.getArtistId(), 0.0);
        double tag = tagAffinity(profile, tags.getOrDefault(song.getId(), Collections.<Long>emptySet()));
        double mood = moodScore(song, profile.mood);
        boolean liked = profile.likedSongIds.contains(song.getId());
        boolean listened = profile.listenedSongIds.contains(song.getId());

        double hotContribution = 0.30 * hot;
        double genreContribution = 0.22 * genre;
        double languageContribution = 0.10 * language;
        double artistContribution = 0.20 * artist;
        double tagContribution = 0.08 * tag;
        double moodContribution = 0.10 * mood;
        double likeContribution = liked ? 0.15 : 0.0;

        ScoredSong scored = new ScoredSong();
        scored.song = song;
        scored.score = hotContribution + genreContribution + languageContribution
                + artistContribution + tagContribution + moodContribution + likeContribution;
        scored.listened = listened;
        scored.liked = liked;

        double best = hotContribution;
        String reason = "最近很火的歌";
        String recall = "hot";

        if (artistContribution > best && artistContribution >= 0.08) {
            best = artistContribution;
            reason = trim("因为你常听" + safe(song.getArtistName()));
            recall = "artist";
        }
        if (genreContribution > best && genreContribution >= 0.08) {
            best = genreContribution;
            reason = trim("你喜欢的" + safe(song.getGenre()) + "风格");
            recall = "genre";
        }
        if (tagContribution > best && tagContribution >= 0.045) {
            best = tagContribution;
            reason = "符合你的口味";
            recall = "tag";
        }
        if (moodContribution > best && moodContribution >= 0.05) {
            best = moodContribution;
            reason = moodReason(profile.mood);
            recall = "mood";
        }
        if (languageContribution > best && languageContribution >= 0.05) {
            reason = trim("你常听" + safe(song.getLanguage()) + "歌");
            recall = "language";
        }
        if (likeContribution > 0) {
            reason = "你喜欢的歌曲";
            recall = "like";
        }
        scored.reason = reason;
        scored.recall = recall;
        return scored;
    }

    public List<ScoredSong> scoreAll(List<Song> songs, TasteProfile profile) {
        List<ScoredSong> scored = new ArrayList<>(songs.size());
        for (Song song : songs) {
            scored.add(score(song, profile));
        }
        scored.sort(Comparator.comparingDouble((ScoredSong s) -> s.score).reversed());
        return scored;
    }

    /**
     * 按业务规则选歌：歌手上限、专辑上限、已听占比上限。
     *
     * <p>已听占比超限时先跳过已听歌曲，第二轮再放开这个限制——
     * 「凑不够」比「轻微超占比」更影响体验，但仍不突破歌手/专辑多样化规则。
     */
    public List<RecommendItemVO> selectDiverse(List<ScoredSong> ranked, int size,
                                               int maxArtist, int maxAlbum,
                                               double listenedRatioLimit, Set<Long> excluded) {
        List<RecommendItemVO> picked = new ArrayList<>(size);
        Set<Long> pickedSongs = new HashSet<>();
        Map<Long, Integer> artistCount = new HashMap<>();
        Map<Long, Integer> albumCount = new HashMap<>();
        int maxListened = (int) Math.floor(size * listenedRatioLimit);
        int listenedPicked = 0;
        Set<Long> skip = excluded == null ? Collections.<Long>emptySet() : excluded;

        for (ScoredSong candidate : ranked) {
            if (picked.size() >= size) {
                break;
            }
            if (candidate.listened && listenedPicked >= maxListened) {
                continue;
            }
            if (tryPick(candidate, picked, pickedSongs, artistCount, albumCount, skip, maxArtist, maxAlbum)) {
                if (candidate.listened) {
                    listenedPicked++;
                }
            }
        }
        // 第二轮：放开已听占比
        if (picked.size() < size) {
            for (ScoredSong candidate : ranked) {
                if (picked.size() >= size) {
                    break;
                }
                tryPick(candidate, picked, pickedSongs, artistCount, albumCount, skip, maxArtist, maxAlbum);
            }
        }
        for (int i = 0; i < picked.size(); i++) {
            picked.get(i).setPosition(i + 1);
        }
        return picked;
    }

    /**
     * 对已经缓存成 {@link RecommendItemVO} 的候选池做同样的多样性选择。
     * 猜你喜欢缓存的是一批条目，翻页时不能再回到 {@link ScoredSong}。
     */
    public List<RecommendItemVO> selectDiverseItems(List<RecommendItemVO> ranked, int size,
                                                   int maxArtist, int maxAlbum,
                                                   double listenedRatioLimit) {
        List<RecommendItemVO> picked = new ArrayList<>(size);
        Set<Long> pickedSongs = new HashSet<>();
        Map<Long, Integer> artistCount = new HashMap<>();
        Map<Long, Integer> albumCount = new HashMap<>();
        int maxListened = (int) Math.floor(size * listenedRatioLimit);
        int listenedPicked = 0;

        for (RecommendItemVO candidate : ranked) {
            if (picked.size() >= size) {
                break;
            }
            boolean listened = Boolean.TRUE.equals(candidate.getListened());
            if (listened && listenedPicked >= maxListened) {
                continue;
            }
            if (tryPickItem(candidate, picked, pickedSongs, artistCount, albumCount, maxArtist, maxAlbum)) {
                if (listened) {
                    listenedPicked++;
                }
            }
        }
        if (picked.size() < size) {
            for (RecommendItemVO candidate : ranked) {
                if (picked.size() >= size) {
                    break;
                }
                tryPickItem(candidate, picked, pickedSongs, artistCount, albumCount, maxArtist, maxAlbum);
            }
        }
        for (int i = 0; i < picked.size(); i++) {
            picked.get(i).setPosition(i + 1);
        }
        return picked;
    }

    private boolean tryPickItem(RecommendItemVO candidate, List<RecommendItemVO> picked, Set<Long> pickedSongs,
                                Map<Long, Integer> artistCount, Map<Long, Integer> albumCount,
                                int maxArtist, int maxAlbum) {
        if (candidate.getSong() == null || pickedSongs.contains(candidate.getSong().getId())) {
            return false;
        }
        Long artistId = candidate.getSong().getArtistId();
        if (artistId != null && artistCount.getOrDefault(artistId, 0) >= maxArtist) {
            return false;
        }
        Long albumId = candidate.getSong().getAlbumId();
        if (albumId != null && albumCount.getOrDefault(albumId, 0) >= maxAlbum) {
            return false;
        }
        picked.add(candidate);
        pickedSongs.add(candidate.getSong().getId());
        if (artistId != null) {
            artistCount.merge(artistId, 1, Integer::sum);
        }
        if (albumId != null) {
            albumCount.merge(albumId, 1, Integer::sum);
        }
        return true;
    }

    /** 冷启动热门池：按热度取，遵守屏蔽与去重。 */
    public List<ScoredSong> hotScored(int limit, Set<Long> excludedArtists) {
        List<ScoredSong> result = new ArrayList<>();
        for (Song song : candidateService.playableSongs()) {
            if (excludedArtists != null && excludedArtists.contains(song.getArtistId())) {
                continue;
            }
            ScoredSong scored = new ScoredSong();
            scored.song = song;
            scored.score = clamp((song.getHotScore() == null ? 0 : song.getHotScore()) / 1000.0);
            scored.reason = "大家都在听";
            scored.recall = "hot";
            result.add(scored);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    public RecommendItemVO toItem(ScoredSong scored, int position) {
        RecommendItemVO item = new RecommendItemVO();
        item.setSong(scored.song);
        item.setReason(trim(scored.reason));
        item.setRecall(scored.recall);
        item.setScore(Math.round(scored.score * 10000d) / 10000d);
        item.setPosition(position);
        item.setListened(scored.listened);
        return item;
    }

    private boolean tryPick(ScoredSong candidate, List<RecommendItemVO> picked, Set<Long> pickedSongs,
                            Map<Long, Integer> artistCount, Map<Long, Integer> albumCount,
                            Set<Long> excluded, int maxArtist, int maxAlbum) {
        if (candidate.song == null || pickedSongs.contains(candidate.song.getId())) {
            return false;
        }
        if (excluded.contains(candidate.song.getId())) {
            return false;
        }
        Long artistId = candidate.song.getArtistId();
        if (artistId != null && artistCount.getOrDefault(artistId, 0) >= maxArtist) {
            return false;
        }
        Long albumId = candidate.song.getAlbumId();
        if (albumId != null && albumCount.getOrDefault(albumId, 0) >= maxAlbum) {
            return false;
        }
        picked.add(toItem(candidate, picked.size() + 1));
        pickedSongs.add(candidate.song.getId());
        if (artistId != null) {
            artistCount.merge(artistId, 1, Integer::sum);
        }
        if (albumId != null) {
            albumCount.merge(albumId, 1, Integer::sum);
        }
        return true;
    }

    private double genreWeight(TasteProfile profile, String genre) {
        if (genre == null) {
            return 0.0;
        }
        Double direct = profile.genreWeights.get(genre);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, Double> entry : profile.genreWeights.entrySet()) {
            if (entry.getKey() != null
                    && (entry.getKey().contains(genre) || genre.contains(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return 0.0;
    }

    private double tagAffinity(TasteProfile profile, Set<Long> tags) {
        if (tags.isEmpty() || profile.tagWeights.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (Long tag : tags) {
            sum += profile.tagWeights.getOrDefault(tag, 0.0);
        }
        return clamp(sum / tags.size());
    }

    private double moodScore(Song song, String mood) {
        if (mood == null || mood.isEmpty()) {
            return 0.0;
        }
        int bpm = song.getBpm() == null ? 0 : song.getBpm();
        switch (mood.toUpperCase()) {
            case "FOCUS":
                return bpm >= 60 && bpm <= 100 ? 1.0 : 0.0;
            case "RELAX":
                return bpm > 0 && bpm <= 90 ? 1.0 : 0.0;
            case "SPORT":
                return bpm >= 120 ? 1.0 : 0.0;
            case "SAD":
                return "民谣".equals(song.getGenre()) || "流行".equals(song.getGenre()) ? 0.8 : 0.0;
            default:
                return 0.0;
        }
    }

    private String moodReason(String mood) {
        if (mood == null) {
            return "换换口味";
        }
        switch (mood.toUpperCase()) {
            case "FOCUS":
                return "适合专注时听";
            case "RELAX":
                return "适合放松时听";
            case "SPORT":
                return "适合运动时听";
            case "SAD":
                return "适合此刻的心情";
            default:
                return "换换口味";
        }
    }

    private void normalize(Map<?, Double> weights) {
        double max = 0;
        for (Double value : weights.values()) {
            if (value != null && value > max) {
                max = value;
            }
        }
        if (max <= 0) {
            return;
        }
        for (Map.Entry<?, Double> entry : weights.entrySet()) {
            entry.setValue(entry.getValue() / max);
        }
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String trim(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "为你推荐";
        }
        return reason.length() <= 20 ? reason : reason.substring(0, 20);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private List<String> split(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String part : csv.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    /** 用户画像：四张归一化权重表 + 行为集合。 */
    public static class TasteProfile {
        private Long userId;
        private long behaviorCount;
        private boolean coldStart;
        private String mood;
        private final Map<Long, Double> artistWeights = new LinkedHashMap<>();
        private final Map<String, Double> genreWeights = new LinkedHashMap<>();
        private final Map<String, Double> languageWeights = new LinkedHashMap<>();
        private final Map<Long, Double> tagWeights = new LinkedHashMap<>();
        private final Set<Long> listenedSongIds = new HashSet<>();
        private final Set<Long> likedSongIds = new HashSet<>();
        private final List<Long> recentSongIds = new ArrayList<>();

        public Long getUserId() {
            return userId;
        }

        public long getBehaviorCount() {
            return behaviorCount;
        }

        public boolean isColdStart() {
            return coldStart;
        }

        public String getMood() {
            return mood;
        }

        public Map<Long, Double> getArtistWeights() {
            return artistWeights;
        }

        public Map<String, Double> getGenreWeights() {
            return genreWeights;
        }

        public Map<String, Double> getLanguageWeights() {
            return languageWeights;
        }

        public Map<Long, Double> getTagWeights() {
            return tagWeights;
        }

        public Set<Long> getListenedSongIds() {
            return listenedSongIds;
        }

        public Set<Long> getLikedSongIds() {
            return likedSongIds;
        }

        public List<Long> getRecentSongIds() {
            return recentSongIds;
        }
    }

    /** 打分后的候选。 */
    public static class ScoredSong {
        private Song song;
        private double score;
        private String reason;
        private String recall;
        private boolean listened;
        private boolean liked;

        public Song getSong() {
            return song;
        }

        public void setSong(Song song) {
            this.song = song;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getRecall() {
            return recall;
        }

        public void setRecall(String recall) {
            this.recall = recall;
        }

        public boolean isListened() {
            return listened;
        }

        public void setListened(boolean listened) {
            this.listened = listened;
        }

        public boolean isLiked() {
            return liked;
        }

        public void setLiked(boolean liked) {
            this.liked = liked;
        }
    }
}
