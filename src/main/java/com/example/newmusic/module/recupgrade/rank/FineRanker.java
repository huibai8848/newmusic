package com.example.newmusic.module.recupgrade.rank;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import com.example.newmusic.module.recupgrade.feature.LocalFeatureService;
import com.example.newmusic.module.recupgrade.model.Candidate;
import com.example.newmusic.module.recupgrade.model.FeatureVector;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.support.RecConfigService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 精排（FR-24-003）。
 *
 * <p>模型形态：<b>规则逻辑回归</b>（PRD 方案 C 兜底）。本机没有 LightGBM/PMML 运行时，
 * 但特征、模型参数、版本热加载与降级链路都按真实系统实现：
 * 参数存放于 {@code ml:rec:model:{version}}（Hash），后台改参数后最多 30 秒生效。
 *
 * <p>多目标融合严格按 PRD：
 * {@code pctr^a × pcvr^b × (1-pskip)^c × (1+plike)^d × hotBoost}。
 * 精排<b>不裁剪</b>（150 首全部打分），裁剪留给重排。
 */
@Slf4j
@Service
public class FineRanker {

    private final LocalFeatureService featureService;
    private final RecConfigService configService;
    private final RecUpgradeProperties properties;
    private final RedisOps redisOps;

    private volatile ModelParameters cachedModel;
    private volatile long modelLoadedAt;

    public FineRanker(LocalFeatureService featureService, RecConfigService configService,
                      RecUpgradeProperties properties, RedisOps redisOps) {
        this.featureService = featureService;
        this.configService = configService;
        this.properties = properties;
        this.redisOps = redisOps;
    }

    public RankResult rank(String scene, List<Candidate> candidates, RecallContext ctx) {
        long start = System.currentTimeMillis();
        if (candidates == null || candidates.isEmpty()) {
            return RankResult.of(new ArrayList<>(), "none", "EMPTY", 0);
        }
        RecConfigService.RankSetting setting = configService.rank(scene);
        RecConfigService.FineSetting fine = setting == null || setting.getFine() == null
                ? new RecConfigService.FineSetting() : setting.getFine();
        String version = fine.getModelVersion() == null ? "rule-lr-v1" : fine.getModelVersion();

        ModelParameters model;
        try {
            model = model(version);
        } catch (RuntimeException e) {
            log.warn("模型加载失败，降级粗排分数 version={} cause={}", version, e.toString());
            return fallback(candidates, version, start);
        }

        FeatureVector features;
        try {
            features = featureService.build(ctx, candidates);
        } catch (RuntimeException e) {
            log.warn("特征服务异常，降级粗排分数 cause={}", e.toString());
            return fallback(candidates, version, start);
        }

        long deadline = start + Math.max(10, properties.getFineRankTimeoutMs());
        boolean timedOut = false;
        for (Candidate candidate : candidates) {
            if (System.currentTimeMillis() > deadline) {
                // EX-24-003-4：超时后用已完成部分，剩余候选退回粗排分数
                candidate.setFineScore(candidate.getCoarseScore());
                candidate.setScoreMode("TIMEOUT_COARSE");
                timedOut = true;
                continue;
            }
            try {
                score(candidate, features, model, fine);
            } catch (RuntimeException e) {
                // EX-24-003-2：单首推理异常不影响整批
                candidate.setFineScore(candidate.getCoarseScore());
                candidate.setPctr(0.5);
                candidate.setPcvr(0.5);
                candidate.setPskip(0.5);
                candidate.setPlike(0.1);
                candidate.setScoreMode("DEFAULT_COARSE");
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::getFineScore).reversed()
                .thenComparing(Candidate::getSongId));
        String mode = timedOut ? "PARTIAL_TIMEOUT" : "MODEL";
        return RankResult.of(candidates, model.getVersion(), mode, System.currentTimeMillis() - start);
    }

    private void score(Candidate candidate, FeatureVector features, ModelParameters model,
                       RecConfigService.FineSetting fine) {
        double hot = features.songFeature(candidate.getSongId(), "hot", candidate.getHotFeature());
        double play = features.songFeature(candidate.getSongId(), "play_log", 0.3);
        double tag = features.songFeature(candidate.getSongId(), "tag_match", candidate.getTagMatch());
        double artist = features.songFeature(candidate.getSongId(), "artist_affinity",
                candidate.getArtistAffinity());
        double fresh = features.songFeature(candidate.getSongId(), "freshness", candidate.getFreshness());
        double completion = features.songFeature(candidate.getSongId(), "completion",
                candidate.getCompletionPrior());
        double skip = features.songFeature(candidate.getSongId(), "skip", candidate.getSkipPrior());
        double likeRate = features.songFeature(candidate.getSongId(), "like_rate", 0.05);
        double sourceCount = features.songFeature(candidate.getSongId(), "source_count",
                candidate.getSourceCount() / 5.0);
        double editor = features.songFeature(candidate.getSongId(), "is_editor",
                candidate.isEditor() ? 1.0 : 0.0);
        double isNew = features.songFeature(candidate.getSongId(), "is_new",
                candidate.isNewSong() ? 1.0 : 0.0);
        double longTail = features.songFeature(candidate.getSongId(), "is_long_tail",
                candidate.isLongTail() ? 1.0 : 0.0);

        double z = model.intercept
                + model.wHot * hot + model.wPlay * play + model.wTag * tag
                + model.wArtist * artist + model.wFresh * fresh
                + model.wCompletion * completion - model.wSkip * skip
                + model.wSource * sourceCount + model.wEditor * editor
                + model.wNew * isNew + model.wLongTail * longTail;
        double pctr = sigmoid(z);

        double zc = model.cvrIntercept + model.cvrCompletion * completion
                + model.cvrTag * tag + model.cvrArtist * artist - model.cvrSkip * skip;
        double pcvr = sigmoid(zc);

        double zl = model.likeIntercept + model.likeRate * likeRate * 10
                + model.likeTag * tag + model.likeEditor * editor;
        double plike = sigmoid(zl);

        double zs = model.skipIntercept + model.skipPrior * skip - model.skipCompletion * completion
                + model.skipLongTail * longTail;
        double pskip = sigmoid(zs);

        candidate.setPctr(pctr);
        candidate.setPcvr(pcvr);
        candidate.setPlike(plike);
        candidate.setPskip(pskip);
        double hotBoost = 1.0 + 0.10 * hot;
        double finalScore = Math.pow(Math.max(1e-6, pctr), fine.getA())
                * Math.pow(Math.max(1e-6, pcvr), fine.getB())
                * Math.pow(Math.max(1e-6, 1.0 - pskip), fine.getC())
                * Math.pow(Math.max(1e-6, 1.0 + plike), fine.getD())
                * hotBoost;
        candidate.setFineScore(clamp(finalScore));
        candidate.setScoreMode("MODEL");
    }

    /** 模型参数热加载：每 30 秒检查一次 Redis；没有自定义参数时使用内置 LR 系数。 */
    private ModelParameters model(String version) {
        long now = System.currentTimeMillis();
        ModelParameters local = cachedModel;
        if (local != null && local.getVersion().equals(version)
                && now - modelLoadedAt < properties.getModelReloadSeconds() * 1000L) {
            return local;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (cachedModel != null && cachedModel.getVersion().equals(version)
                    && now - modelLoadedAt < properties.getModelReloadSeconds() * 1000L) {
                return cachedModel;
            }
            ModelParameters loaded = ModelParameters.defaults(version);
            Map<String, String> params = redisOps.hashGetAll(RedisKeys.recModel(version));
            if (!params.isEmpty()) {
                loaded.apply(params);
            }
            cachedModel = loaded;
            modelLoadedAt = now;
            return loaded;
        }
    }

    private RankResult fallback(List<Candidate> candidates, String version, long start) {
        for (Candidate candidate : candidates) {
            candidate.setFineScore(candidate.getCoarseScore());
            candidate.setPctr(0.5);
            candidate.setPcvr(0.5);
            candidate.setPskip(0.5);
            candidate.setPlike(0.1);
            candidate.setScoreMode("COARSE_FALLBACK");
        }
        candidates.sort(Comparator.comparingDouble(Candidate::getFineScore).reversed()
                .thenComparing(Candidate::getSongId));
        return RankResult.of(candidates, version, "COARSE_FALLBACK", System.currentTimeMillis() - start);
    }

    public void invalidateModel() {
        cachedModel = null;
        modelLoadedAt = 0;
    }

    /** 后台展示：当前生效的模型版本与全部系数（FR-24-003 的「模型可解释」）。 */
    public Map<String, Object> modelSnapshot(String scene) {
        RecConfigService.RankSetting setting = configService.rank(scene);
        RecConfigService.FineSetting fine = setting == null || setting.getFine() == null
                ? new RecConfigService.FineSetting() : setting.getFine();
        String version = fine.getModelVersion() == null ? "rule-lr-v1" : fine.getModelVersion();
        ModelParameters params = model(version);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("version", params.getVersion());
        result.put("loadedAt", modelLoadedAt);
        result.put("reloadSeconds", properties.getModelReloadSeconds());
        result.put("exponents", java.util.Arrays.asList(fine.getA(), fine.getB(), fine.getC(), fine.getD()));
        result.put("params", params.snapshot());
        return result;
    }

    private double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-Math.max(-30, Math.min(30, z))));
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** 线性模型参数。字段名与 Redis Hash 的键一一对应，后台可直接改其中任意一项。 */
    @Data
    public static class ModelParameters {
        private String version = "rule-lr-v1";
        private double intercept = -1.2;
        private double wHot = 1.5;
        private double wPlay = 0.9;
        private double wTag = 1.0;
        private double wArtist = 1.2;
        private double wFresh = 0.6;
        private double wCompletion = 0.8;
        private double wSkip = 1.2;
        private double wSource = 0.7;
        private double wEditor = 1.0;
        private double wNew = 0.3;
        private double wLongTail = -0.2;

        private double cvrIntercept = 0.2;
        private double cvrCompletion = 4.0;
        private double cvrTag = 0.5;
        private double cvrArtist = 0.4;
        private double cvrSkip = 2.0;

        private double likeIntercept = -2.5;
        private double likeRate = 0.5;
        private double likeTag = 0.8;
        private double likeEditor = 0.3;

        private double skipIntercept = -0.5;
        private double skipPrior = 4.0;
        private double skipCompletion = 2.0;
        private double skipLongTail = 0.3;

        public static ModelParameters defaults(String version) {
            ModelParameters model = new ModelParameters();
            model.version = version == null ? "rule-lr-v1" : version;
            return model;
        }

        public void apply(Map<String, String> params) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                try {
                    double value = Double.parseDouble(entry.getValue());
                    switch (entry.getKey()) {
                        case "intercept": intercept = value; break;
                        case "wHot": wHot = value; break;
                        case "wPlay": wPlay = value; break;
                        case "wTag": wTag = value; break;
                        case "wArtist": wArtist = value; break;
                        case "wFresh": wFresh = value; break;
                        case "wCompletion": wCompletion = value; break;
                        case "wSkip": wSkip = value; break;
                        case "wSource": wSource = value; break;
                        case "wEditor": wEditor = value; break;
                        case "wNew": wNew = value; break;
                        case "wLongTail": wLongTail = value; break;
                        case "cvrIntercept": cvrIntercept = value; break;
                        case "cvrCompletion": cvrCompletion = value; break;
                        case "cvrTag": cvrTag = value; break;
                        case "cvrArtist": cvrArtist = value; break;
                        case "cvrSkip": cvrSkip = value; break;
                        case "likeIntercept": likeIntercept = value; break;
                        case "likeRate": likeRate = value; break;
                        case "likeTag": likeTag = value; break;
                        case "likeEditor": likeEditor = value; break;
                        case "skipIntercept": skipIntercept = value; break;
                        case "skipPrior": skipPrior = value; break;
                        case "skipCompletion": skipCompletion = value; break;
                        case "skipLongTail": skipLongTail = value; break;
                        default: break;
                    }
                } catch (NumberFormatException ignored) {
                    // 单个坏参数不应让整个模型加载失败
                }
            }
        }

        /** 后台展示用：参数快照。 */
        public Map<String, Double> snapshot() {
            Map<String, Double> map = new LinkedHashMap<>();
            map.put("intercept", intercept);
            map.put("wHot", wHot);
            map.put("wPlay", wPlay);
            map.put("wTag", wTag);
            map.put("wArtist", wArtist);
            map.put("wFresh", wFresh);
            map.put("wCompletion", wCompletion);
            map.put("wSkip", wSkip);
            map.put("wSource", wSource);
            map.put("wEditor", wEditor);
            map.put("wNew", wNew);
            map.put("wLongTail", wLongTail);
            return map;
        }
    }
}
