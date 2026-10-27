package com.example.newmusic.module.recupgrade.recall.source;

import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.model.RecallResult;
import com.example.newmusic.module.recupgrade.recall.RecallDataService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 情绪召回：问卷/播放上下文给出的心情（FOCUS/RELAX/SPORT/SAD）匹配 BPM 与流派。
 * 规则与 {@code ScoringService} 保持一致，避免「同一个心情在两处给出不同结果」。
 */
@Component
public class EmotionMatchRecallSource extends AbstractRecallSource {

    public EmotionMatchRecallSource(RecallDataService data) {
        super(data);
    }

    @Override
    public String name() {
        return "emotion_match";
    }

    @Override
    public boolean enabled(RecallContext ctx) {
        return ctx.hasUser() && ctx.getProfile() != null
                && ctx.getProfile().getMood() != null && !ctx.getProfile().getMood().isEmpty();
    }

    @Override
    public int timeoutMs() {
        return 60;
    }

    @Override
    public int priority() {
        return 35;
    }

    @Override
    public RecallResult recall(RecallContext ctx) {
        long start = System.currentTimeMillis();
        String mood = ctx.getProfile().getMood();
        List<Long> ids = new ArrayList<>();
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (Song song : data.playableSongs()) {
            double score = moodScore(song, mood);
            if (score > 0) {
                ids.add(song.getId());
                scores.put(song.getId(), score);
            }
            if (ids.size() >= ctx.getLimit() * 2) {
                break;
            }
        }
        return RecallResult.ok(name(), collect(ctx, ids, scores), System.currentTimeMillis() - start);
    }

    private double moodScore(Song song, String mood) {
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
}
