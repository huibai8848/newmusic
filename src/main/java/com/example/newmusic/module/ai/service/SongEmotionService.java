package com.example.newmusic.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.ai.config.AiProperties;
import com.example.newmusic.module.ai.dto.EmotionTagResult;
import com.example.newmusic.module.ai.dto.EmotionUpdateReq;
import com.example.newmusic.module.ai.dto.SongEmotionVO;
import com.example.newmusic.module.ai.entity.AiAnnotationFail;
import com.example.newmusic.module.ai.entity.SongEmotion;
import com.example.newmusic.module.ai.gateway.AiGateway;
import com.example.newmusic.module.ai.gateway.AiRequest;
import com.example.newmusic.module.ai.gateway.AiResponse;
import com.example.newmusic.module.ai.mapper.SongEmotionMapper;
import com.example.newmusic.module.ai.support.AiEmotionVocabulary;
import com.example.newmusic.module.ai.support.AiFailureQueue;
import com.example.newmusic.module.ai.support.AiJobProgress;
import com.example.newmusic.module.ai.support.LrcSupport;
import com.example.newmusic.module.music.entity.Lyric;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.LyricMapper;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 歌曲情绪 / 场景标注（FR-23-004）。
 *
 * <p>三条不可退让的规则：
 * <ol>
 *   <li><b>白名单</b>：模型给的标签不在 {@link AiEmotionVocabulary} 里一律丢弃；</li>
 *   <li><b>MANUAL 保护</b>：批处理只处理没有标注的歌，永远不会覆盖运营手工结果；</li>
 *   <li><b>低置信度转人工</b>：置信度低于阈值仍入库，但会进后台「待复核」列表——
 *       直接丢弃会让「这首歌为什么没有标签」无从排查。</li>
 * </ol>
 *
 * <p>数值统一夹到 0~1。模型偶尔会返回 85（百分制）或 -0.3，不夹取会让筛选条件失真。
 */
@Slf4j
@Service
public class SongEmotionService {

    private static final String SYSTEM_PROMPT =
            "你是音乐情绪分析助手，只输出 JSON，不输出任何解释。";
    private static final String PROMPT_TEMPLATE =
            "分析歌曲的情绪与适用场景。\n"
            + "歌曲：《%s》\n歌手：%s\n歌词：\n%s\n\n"
            + "只返回 JSON 对象：{\"valence\":0~1,\"arousal\":0~1,\"energy\":0~1,"
            + "\"emotionTags\":[...],\"sceneTags\":[...],\"confidence\":0~1,\"reason\":\"30字以内\"}\n"
            + "情绪标签只能从这些词里选（最多3个）：%s\n"
            + "场景标签只能从这些词里选（最多3个）：%s";
    private static final int MAX_LYRIC_CHARS = 1200;
    private static final int MAX_REASON_LEN = 200;

    private final SongMapper songMapper;
    private final LyricMapper lyricMapper;
    private final SongEmotionMapper emotionMapper;
    private final AiFailureQueue failureQueue;
    private final AiGateway aiGateway;
    private final AiProperties properties;
    private final AiJobProgress jobProgress;
    private final ObjectMapper objectMapper;

    public SongEmotionService(SongMapper songMapper, LyricMapper lyricMapper,
                              SongEmotionMapper emotionMapper, AiFailureQueue failureQueue,
                              AiGateway aiGateway, AiProperties properties,
                              AiJobProgress jobProgress, ObjectMapper objectMapper) {
        this.songMapper = songMapper;
        this.lyricMapper = lyricMapper;
        this.emotionMapper = emotionMapper;
        this.failureQueue = failureQueue;
        this.aiGateway = aiGateway;
        this.properties = properties;
        this.jobProgress = jobProgress;
        this.objectMapper = objectMapper;
    }

    public SongEmotionVO get(Long songId) {
        Song song = requireSong(songId);
        SongEmotion emotion = findBySong(songId);
        if (emotion == null) {
            return SongEmotionVO.builder()
                    .songId(songId)
                    .songName(song.getName())
                    .annotated(false)
                    .valence(0.5)
                    .arousal(0.5)
                    .energy(0.5)
                    .emotionTags(Collections.<String>emptyList())
                    .sceneTags(Collections.<String>emptyList())
                    .confidence(0)
                    .build();
        }
        return toVO(song, emotion);
    }

    /**
     * 标注单曲。
     *
     * @param force true = 忽略已有的 AI 结果并绕过网关缓存重新生成（后台「重新标注」）；
     *              MANUAL 记录在非 force 时永远原样返回
     */
    public SongEmotionVO annotate(Long songId, Long userId, boolean force) {
        Song song = requireSong(songId);
        SongEmotion existing = findBySong(songId);
        if (existing != null && SongEmotion.SOURCE_MANUAL.equals(existing.getSource()) && !force) {
            return toVO(song, existing);
        }

        String lyricText = lyricTextOf(songId);
        String prompt = String.format(PROMPT_TEMPLATE,
                song.getName(),
                song.getArtistName() == null ? "" : song.getArtistName(),
                lyricText,
                String.join("、", AiEmotionVocabulary.EMOTION_TAGS),
                String.join("、", AiEmotionVocabulary.SCENE_TAGS));

        AiRequest request = AiRequest.builder()
                .scene("EMOTION_TAG")
                .systemPrompt(SYSTEM_PROMPT)
                .userPrompt(prompt)
                .temperature(0.2)
                .userId(userId)
                .bizId(String.valueOf(songId))
                // force 时必须绕过缓存，否则「重新标注」只会拿到上一次的结果
                .cacheTtl(force ? Duration.ZERO : null)
                .build();
        AiResponse<EmotionTagResult> response = aiGateway.chatStructured(request, EmotionTagResult.class);
        EmotionTagResult result = response.getData();
        if (result == null) {
            throw new BizException(ResultCode.AI_UNAVAILABLE, "AI 未返回有效的情绪标注");
        }

        double valence = clamp(result.getValence());
        double arousal = clamp(result.getArousal());
        double energy = clamp(result.getEnergy());
        double confidence = result.getConfidence() == null ? 0.5 : clamp(result.getConfidence());
        List<String> emotions = AiEmotionVocabulary.sanitizeEmotions(result.getEmotionTags());
        List<String> scenes = AiEmotionVocabulary.sanitizeScenes(result.getSceneTags());
        if (emotions.isEmpty()) {
            // 白名单过滤后为空：给一个中性标签，同时把置信度压到阈值以下，进入人工复核
            emotions = Collections.singletonList("平静");
            confidence = Math.min(confidence, 0.5);
        }
        if (scenes.isEmpty()) {
            scenes = Collections.singletonList("通勤");
        }

        SongEmotion entity = existing == null ? new SongEmotion() : existing;
        entity.setSongId(songId);
        entity.setValence(BigDecimal.valueOf(valence));
        entity.setArousal(BigDecimal.valueOf(arousal));
        entity.setEnergy(BigDecimal.valueOf(energy));
        entity.setEmotionTags(writeJson(emotions));
        entity.setSceneTags(writeJson(scenes));
        entity.setConfidence(BigDecimal.valueOf(confidence));
        entity.setSource(SongEmotion.SOURCE_AI);
        entity.setReason(truncate(result.getReason()));
        entity.setModelVersion(response.getModel());
        entity.setPromptVersion(response.getPromptVersion());
        if (entity.getId() == null) {
            emotionMapper.insert(entity);
        } else {
            emotionMapper.updateById(entity);
        }
        failureQueue.clear("EMOTION_TAG", String.valueOf(songId));
        return toVO(song, entity);
    }

    /**
     * 批量标注待处理歌曲。返回本轮统计；进度写 Redis，供后台轮询。
     */
    public Map<String, Object> annotateBatch(String jobId, int limit) {
        List<SongEmotionMapper.AnnotationCandidate> candidates =
                emotionMapper.selectAnnotationCandidates(limit);
        String id = StringUtils.hasText(jobId) ? jobId : jobProgress.newJobId("emotion");
        jobProgress.start(id, candidates.size());
        int done = 0;
        int failed = 0;
        for (SongEmotionMapper.AnnotationCandidate candidate : candidates) {
            try {
                annotate(candidate.getSongId(), null, false);
                done++;
            } catch (RuntimeException e) {
                failed++;
                failureQueue.record("EMOTION_TAG", String.valueOf(candidate.getSongId()), e.getMessage());
                log.warn("情绪标注失败 songId={} cause={}", candidate.getSongId(), e.toString());
            }
            jobProgress.update(id, done, failed);
            sleepQuietly();
        }
        jobProgress.finish(id, done, failed, failed > 0 ? "FINISHED_WITH_ERRORS" : "FINISHED");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("jobId", id);
        summary.put("total", candidates.size());
        summary.put("done", done);
        summary.put("failed", failed);
        return summary;
    }

    /** 重试失败队列；成功一条删一条。 */
    public int retryFailed(int limit) {
        List<AiAnnotationFail> rows = failureQueue.due("EMOTION_TAG", limit);
        int ok = 0;
        for (AiAnnotationFail row : rows) {
            try {
                annotate(Long.parseLong(row.getBizId()), null, false);
                failureQueue.remove(row.getId());
                ok++;
            } catch (RuntimeException e) {
                failureQueue.record(row.getScene(), row.getBizId(), e.getMessage());
            }
        }
        return ok;
    }

    /** 运营手工修正；写入后 {@code source=MANUAL}，批处理不再覆盖。 */
    @Transactional
    public SongEmotionVO updateManual(Long songId, EmotionUpdateReq req) {
        Song song = requireSong(songId);
        List<String> emotions = AiEmotionVocabulary.sanitizeEmotions(req.getEmotionTags());
        if (emotions.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "情绪标签必须是白名单内的词");
        }
        List<String> scenes = AiEmotionVocabulary.sanitizeScenes(req.getSceneTags());
        SongEmotion emotion = findBySong(songId);
        if (emotion == null) {
            emotion = new SongEmotion();
            emotion.setSongId(songId);
        }
        emotion.setValence(BigDecimal.valueOf(clamp(req.getValence())));
        emotion.setArousal(BigDecimal.valueOf(clamp(req.getArousal())));
        emotion.setEnergy(BigDecimal.valueOf(clamp(req.getEnergy())));
        emotion.setEmotionTags(writeJson(emotions));
        emotion.setSceneTags(writeJson(scenes));
        emotion.setConfidence(BigDecimal.ONE);
        emotion.setSource(SongEmotion.SOURCE_MANUAL);
        emotion.setReason(StringUtils.hasText(req.getReason()) ? truncate(req.getReason()) : "运营手工标注");
        emotion.setModelVersion("manual");
        emotion.setPromptVersion(null);
        if (emotion.getId() == null) {
            emotionMapper.insert(emotion);
        } else {
            emotionMapper.updateById(emotion);
        }
        failureQueue.clear("EMOTION_TAG", String.valueOf(songId));
        return toVO(song, emotion);
    }

    public List<Map<String, Object>> lowConfidence(int limit) {
        List<SongEmotionMapper.LowConfidenceRow> rows = emotionMapper.selectLowConfidence(
                BigDecimal.valueOf(properties.getLowConfidence()), limit);
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (SongEmotionMapper.LowConfidenceRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("songId", row.getSongId());
            item.put("songName", row.getSongName());
            item.put("confidence", row.getConfidence());
            item.put("emotionTags", readJson(row.getEmotionTags()));
            item.put("sceneTags", readJson(row.getSceneTags()));
            item.put("reason", row.getReason());
            item.put("updateTime", row.getUpdateTime());
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> progress(String jobId) {
        return jobProgress.get(jobId);
    }

    public long countAnnotated() {
        return emotionMapper.countAnnotated();
    }

    // =========================================================================

    private Song requireSong(Long songId) {
        Song song = songMapper.selectById(songId);
        if (song == null) {
            throw new BizException(ResultCode.SONG_NOT_FOUND);
        }
        return song;
    }

    private SongEmotion findBySong(Long songId) {
        return emotionMapper.selectOne(new QueryWrapper<SongEmotion>()
                .eq("song_id", songId)
                .last("LIMIT 1"));
    }

    private String lyricTextOf(Long songId) {
        List<Lyric> lyrics = lyricMapper.selectList(new QueryWrapper<Lyric>()
                .eq("song_id", songId)
                .orderByDesc("id")
                .last("LIMIT 1"));
        if (lyrics.isEmpty() || !StringUtils.hasText(lyrics.get(0).getContent())) {
            return "（无歌词）";
        }
        List<String> texts = LrcSupport.uniqueTexts(LrcSupport.parse(lyrics.get(0).getContent()));
        if (texts.isEmpty()) {
            return "（无歌词）";
        }
        String joined = String.join("\n", texts);
        return joined.length() <= MAX_LYRIC_CHARS ? joined : joined.substring(0, MAX_LYRIC_CHARS);
    }

    private double clamp(Double value) {
        return value == null ? 0.5 : AiEmotionVocabulary.clamp(value);
    }

    private String truncate(String reason) {
        if (!StringUtils.hasText(reason)) {
            return null;
        }
        String text = reason.trim();
        return text.length() <= MAX_REASON_LEN ? text : text.substring(0, MAX_REASON_LEN);
    }

    private String writeJson(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> readJson(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private SongEmotionVO toVO(Song song, SongEmotion emotion) {
        double confidence = emotion.getConfidence() == null ? 0 : emotion.getConfidence().doubleValue();
        return SongEmotionVO.builder()
                .songId(song.getId())
                .songName(song.getName())
                .annotated(true)
                .valence(emotion.getValence() == null ? 0.5 : emotion.getValence().doubleValue())
                .arousal(emotion.getArousal() == null ? 0.5 : emotion.getArousal().doubleValue())
                .energy(emotion.getEnergy() == null ? 0.5 : emotion.getEnergy().doubleValue())
                .emotionTags(readJson(emotion.getEmotionTags()))
                .sceneTags(readJson(emotion.getSceneTags()))
                .confidence(confidence)
                .source(emotion.getSource())
                .reason(emotion.getReason())
                .modelVersion(emotion.getModelVersion())
                .promptVersion(emotion.getPromptVersion())
                .lowConfidence(confidence < properties.getLowConfidence())
                .updatedAt(emotion.getUpdateTime())
                .build();
    }

    private void sleepQuietly() {
        long interval = properties.getBatchIntervalMs();
        if (interval <= 0) {
            return;
        }
        try {
            Thread.sleep(interval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
