package com.example.newmusic.module.recommend.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.recommend.dto.QuestionnaireReq;
import com.example.newmusic.module.recommend.entity.UserPreference;
import com.example.newmusic.module.recommend.mapper.UserPreferenceMapper;
import com.example.newmusic.module.recommend.vo.QuestionnaireVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冷启动口味问卷（FR-17-006）。
 *
 * <p>问卷可跳过；提交后写入 {@code t_user_preference} 并立即失效推荐缓存，
 * 让用户下一次请求就看到偏好结果。
 */
@Service
public class QuestionnaireService {

    private static final List<String> STYLE_OPTIONS = Collections.unmodifiableList(Arrays.asList(
            "流行", "摇滚", "说唱", "民谣", "电子", "古风", "爵士", "R&B", "轻音乐"));
    private static final List<String> LANGUAGE_OPTIONS = Collections.unmodifiableList(Arrays.asList(
            "国语", "粤语", "英语", "日语", "韩语", "纯音乐"));
    private static final Map<String, String> MOOD_OPTIONS;

    static {
        Map<String, String> moods = new LinkedHashMap<>();
        moods.put("FOCUS", "专注工作");
        moods.put("RELAX", "放松休息");
        moods.put("SPORT", "运动健身");
        moods.put("SAD", "心情低落");
        moods.put("JUST_LISTEN", "只是想听点音乐");
        MOOD_OPTIONS = Collections.unmodifiableMap(moods);
    }

    private final UserPreferenceMapper preferenceMapper;
    private final RecommendCache recommendCache;

    public QuestionnaireService(UserPreferenceMapper preferenceMapper, RecommendCache recommendCache) {
        this.preferenceMapper = preferenceMapper;
        this.recommendCache = recommendCache;
    }

    public QuestionnaireVO get(Long userId) {
        QuestionnaireVO vo = new QuestionnaireVO();
        vo.setStyleOptions(STYLE_OPTIONS);
        vo.setLanguageOptions(LANGUAGE_OPTIONS);
        vo.setMoodOptions(MOOD_OPTIONS);
        UserPreference preference = find(userId);
        if (preference != null) {
            QuestionnaireVO.PreferenceVO current = new QuestionnaireVO.PreferenceVO();
            current.setStyles(split(preference.getStyles()));
            current.setLanguages(split(preference.getLanguages()));
            current.setArtists(splitLong(preference.getArtists()));
            current.setMood(preference.getMood());
            vo.setCurrent(current);
        }
        return vo;
    }

    @Transactional
    public QuestionnaireVO save(Long userId, QuestionnaireReq req) {
        for (String style : safe(req.getStyles())) {
            if (!STYLE_OPTIONS.contains(style)) {
                throw new BizException(ResultCode.PARAM_INVALID, "不支持的音乐风格：" + style);
            }
        }
        for (String language : safe(req.getLanguages())) {
            if (!LANGUAGE_OPTIONS.contains(language)) {
                throw new BizException(ResultCode.PARAM_INVALID, "不支持的语种：" + language);
            }
        }
        if (req.getMood() != null && !req.getMood().isEmpty()
                && !MOOD_OPTIONS.containsKey(req.getMood().toUpperCase())) {
            throw new BizException(ResultCode.PARAM_INVALID, "不支持的心情：" + req.getMood());
        }

        UserPreference preference = find(userId);
        if (preference == null) {
            preference = new UserPreference();
            preference.setUserId(userId);
            preference.setSource(1);
        }
        preference.setStyles(join(req.getStyles()));
        preference.setLanguages(join(req.getLanguages()));
        preference.setArtists(joinLong(req.getArtists()));
        preference.setMood(req.getMood() == null ? null : req.getMood().toUpperCase());
        if (preference.getId() == null) {
            preferenceMapper.insert(preference);
        } else {
            preferenceMapper.updateById(preference);
        }

        recommendCache.invalidateUser(userId);
        return get(userId);
    }

    private UserPreference find(Long userId) {
        return preferenceMapper.selectOne(Wrappers.<UserPreference>lambdaQuery()
                .eq(UserPreference::getUserId, userId)
                .orderByDesc(UserPreference::getUpdateTime)
                .last("LIMIT 1"));
    }

    private List<String> safe(List<String> values) {
        return values == null ? Collections.<String>emptyList() : values;
    }

    private String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                cleaned.add(value.trim());
            }
        }
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    private String joinLong(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (Long value : values) {
            if (value != null) {
                cleaned.add(String.valueOf(value));
            }
        }
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    private List<String> split(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(Arrays.asList(csv.split(",")));
    }

    private List<Long> splitLong(String csv) {
        List<Long> result = new ArrayList<>();
        for (String value : split(csv)) {
            try {
                result.add(Long.valueOf(value.trim()));
            } catch (NumberFormatException ignored) {
                // 脏数据
            }
        }
        return result;
    }
}
