package com.example.newmusic.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.ai.dto.LyricTranslationVO;
import com.example.newmusic.module.ai.entity.AiFeedback;
import com.example.newmusic.module.ai.gateway.AiGateway;
import com.example.newmusic.module.ai.gateway.AiRequest;
import com.example.newmusic.module.ai.gateway.AiResponse;
import com.example.newmusic.module.ai.mapper.AiFeedbackMapper;
import com.example.newmusic.module.ai.support.LrcSupport;
import com.example.newmusic.module.music.entity.Lyric;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.LyricMapper;
import com.example.newmusic.module.music.mapper.SongMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 歌词翻译（FR-23-002）。
 *
 * <p>流程：校验语种/歌词 → 命中已有译文则直接返回 → 调网关 → **校验行数** → 重建 LRC →
 * 落库 + 写永久缓存 → 标记 {@code t_song.has_translation}。
 *
 * <p><b>行数校验是硬门槛。</b>模型多输出一行少输出一行，按行号对齐就会整体错位，
 * 用户看到的是「第二句配第三句的译文」，而且很难发现。宁可失败后再试，也不保存错位的结果。
 *
 * <p><b>缓存永久有效</b>（BR-23-019）。歌词不会变，但 Prompt 版本变了指纹会变，
 * 旧缓存自然失效；人工修正译文后需要删除 {@code ml:ai:lyric:trans:{songId}}，
 * 后台「重新生成」接口负责这件事。
 */
@Slf4j
@Service
public class LyricTranslationService {

    public static final String TRANS_LANG = "zh-CN";
    private static final String SYSTEM_PROMPT = "你是专业的歌词翻译，只输出译文，不解释、不加标题。";
    private static final String PROMPT_TEMPLATE =
            "将下面的歌词逐行翻译成简体中文。要求：\n"
            + "1. 每行对应一行译文，不得合并或拆分行；\n"
            + "2. 不要输出行号、时间轴或任何解释；\n"
            + "3. 保留原文的语气与意象。\n"
            + "<<<\n%s\n>>>";

    private final SongMapper songMapper;
    private final LyricMapper lyricMapper;
    private final AiGateway aiGateway;
    private final RedisOps redisOps;
    private final AiFeedbackMapper feedbackMapper;

    public LyricTranslationService(SongMapper songMapper, LyricMapper lyricMapper,
                                   AiGateway aiGateway, RedisOps redisOps,
                                   AiFeedbackMapper feedbackMapper) {
        this.songMapper = songMapper;
        this.lyricMapper = lyricMapper;
        this.aiGateway = aiGateway;
        this.redisOps = redisOps;
        this.feedbackMapper = feedbackMapper;
    }

    /** 读取已有译文；没有生成过时返回 {@code generated=false}，由前端决定是否触发生成。 */
    public LyricTranslationVO get(Long songId) {
        Song song = requireSong(songId);
        Lyric lyric = requireLyric(songId);
        ensureTranslatable(song, lyric);

        String cached = redisOps.get(RedisKeys.AI_LYRIC_TRANS + songId).orElse(null);
        if (StringUtils.hasText(cached)) {
            return toVO(song, lyric, cached, true, null, 0);
        }
        if (StringUtils.hasText(lyric.getTranslation())) {
            // DB 有译文而 Redis 没有：补一次永久缓存，后续读取不再回表
            redisOps.set(RedisKeys.AI_LYRIC_TRANS + songId, lyric.getTranslation(), Duration.ZERO);
            return toVO(song, lyric, lyric.getTranslation(), false, null, 0);
        }
        return toVO(song, lyric, null, false, null, 0);
    }

    /** 生成译文；已存在则幂等返回。 */
    public LyricTranslationVO generate(Long songId, Long userId) {
        Song song = requireSong(songId);
        Lyric lyric = requireLyric(songId);
        ensureTranslatable(song, lyric);

        if (StringUtils.hasText(lyric.getTranslation())) {
            redisOps.set(RedisKeys.AI_LYRIC_TRANS + songId, lyric.getTranslation(), Duration.ZERO);
            return toVO(song, lyric, lyric.getTranslation(), false, null, 0);
        }

        List<LrcSupport.LrcLine> lines = LrcSupport.parse(lyric.getContent());
        List<String> originals = LrcSupport.uniqueTexts(lines);
        if (originals.isEmpty()) {
            throw new BizException(ResultCode.AI_NOT_APPLICABLE, "暂无可翻译的歌词");
        }

        AiRequest request = AiRequest.builder()
                .scene("LYRIC_TRANS")
                .systemPrompt(SYSTEM_PROMPT)
                .userPrompt(String.format(PROMPT_TEMPLATE, String.join("\n", originals)))
                .temperature(0.3)
                .userId(userId)
                .bizId(String.valueOf(songId))
                .build();
        AiResponse<String> response = aiGateway.chat(request);

        List<String> translations = LrcSupport.splitTranslations(response.getContent());
        if (translations.size() != originals.size()) {
            log.warn("翻译行数不一致 songId={} expect={} actual={}",
                    songId, originals.size(), translations.size());
            throw new BizException(ResultCode.AI_UNAVAILABLE, "翻译结果与歌词行数不一致，请稍后重试");
        }
        for (String line : translations) {
            if (!StringUtils.hasText(line)) {
                throw new BizException(ResultCode.AI_UNAVAILABLE, "翻译结果存在空行，请稍后重试");
            }
        }

        String translationLrc = LrcSupport.build(lines, LrcSupport.zip(originals, translations));
        saveTranslation(song, lyric, translationLrc);
        // 用刚生成的译文构造 VO，避免再查一次歌词表
        return toVO(song, lyric, translationLrc, false, response.getModel(), response.getCostCent());
    }

    /** 用户反馈翻译问题（BR-23-024）。 */
    public Long feedback(Long songId, Long userId, String issue) {
        Song song = requireSong(songId);
        Lyric lyric = requireLyric(songId);
        ensureTranslatable(song, lyric);
        if (!StringUtils.hasText(lyric.getTranslation())) {
            throw new BizException(ResultCode.PARAM_INVALID, "该歌曲还没有翻译，无法反馈");
        }
        AiFeedback feedback = new AiFeedback();
        feedback.setScene("LYRIC_TRANS");
        feedback.setSongId(songId);
        feedback.setUserId(userId);
        feedback.setIssue(issue.trim());
        feedback.setStatus(AiFeedback.STATUS_PENDING);
        feedbackMapper.insert(feedback);
        return feedback.getId();
    }

    /** 运营修正译文后调用：覆盖数据库与缓存。 */
    @Transactional
    public void overwrite(Long songId, String translationLrc) {
        Song song = requireSong(songId);
        Lyric lyric = requireLyric(songId);
        if (!StringUtils.hasText(translationLrc)) {
            throw new BizException(ResultCode.PARAM_INVALID, "译文不能为空");
        }
        saveTranslation(song, lyric, translationLrc);
    }

    private void saveTranslation(Song song, Lyric lyric, String translationLrc) {
        lyric.setTranslation(translationLrc);
        lyric.setTransLang(TRANS_LANG);
        lyricMapper.updateById(lyric);

        if (song.getHasTranslation() == null || song.getHasTranslation() != 1) {
            song.setHasTranslation(1);
            songMapper.updateById(song);
        }
        redisOps.set(RedisKeys.AI_LYRIC_TRANS + song.getId(), translationLrc, Duration.ZERO);
    }

    private Song requireSong(Long songId) {
        Song song = songMapper.selectById(songId);
        if (song == null) {
            throw new BizException(ResultCode.SONG_NOT_FOUND);
        }
        return song;
    }

    private Lyric requireLyric(Long songId) {
        List<Lyric> lyrics = lyricMapper.selectList(new QueryWrapper<Lyric>()
                .eq("song_id", songId)
                .orderByDesc("id")
                .last("LIMIT 1"));
        if (lyrics.isEmpty()) {
            throw new BizException(ResultCode.AI_NOT_APPLICABLE, "暂无歌词");
        }
        Lyric lyric = lyrics.get(0);
        if (!StringUtils.hasText(lyric.getContent())) {
            throw new BizException(ResultCode.AI_NOT_APPLICABLE, "暂无歌词");
        }
        return lyric;
    }

    private void ensureTranslatable(Song song, Lyric lyric) {
        String language = song.getLanguage();
        if (StringUtils.hasText(language) && isChineseLanguage(language)) {
            throw new BizException(ResultCode.AI_NOT_APPLICABLE, "中文歌曲无需翻译");
        }
        if (!StringUtils.hasText(language) && LrcSupport.isMostlyChinese(lyric.getContent())) {
            throw new BizException(ResultCode.AI_NOT_APPLICABLE, "中文歌曲无需翻译");
        }
        if (LrcSupport.parse(lyric.getContent()).isEmpty()) {
            throw new BizException(ResultCode.AI_NOT_APPLICABLE, "暂无歌词");
        }
    }

    private boolean isChineseLanguage(String language) {
        return language.contains("中文") || language.contains("国语")
                || language.contains("普通话") || language.contains("汉语")
                || language.contains("粤语");
    }

    private LyricTranslationVO toVO(Song song, Lyric lyric, String translationLrc, boolean fromCache,
                                    String model, int costCent) {
        List<LrcSupport.LrcLine> originals = LrcSupport.parse(lyric.getContent());
        Map<Long, String> translated = new LinkedHashMap<>();
        if (StringUtils.hasText(translationLrc)) {
            for (LrcSupport.LrcLine line : LrcSupport.parse(translationLrc)) {
                translated.put(line.getTimeMs(), line.getText());
            }
        }
        List<LyricTranslationVO.Line> lines = new ArrayList<>(originals.size());
        for (LrcSupport.LrcLine line : originals) {
            lines.add(new LyricTranslationVO.Line(line.getTimeMs(), line.getText(),
                    translated.get(line.getTimeMs())));
        }
        return LyricTranslationVO.builder()
                .songId(song.getId())
                .songName(song.getName())
                .language(song.getLanguage())
                .transLang(TRANS_LANG)
                .generated(StringUtils.hasText(translationLrc))
                .fromCache(fromCache)
                .model(model)
                .costCent(costCent)
                .translation(translationLrc)
                .lines(lines)
                .build();
    }
}
