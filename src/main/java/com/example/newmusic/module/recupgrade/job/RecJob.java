package com.example.newmusic.module.recupgrade.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import com.example.newmusic.module.recupgrade.entity.RecFeatureConsistency;
import com.example.newmusic.module.recupgrade.entity.RecLog;
import com.example.newmusic.module.recupgrade.entity.RecMetricDaily;
import com.example.newmusic.module.recupgrade.entity.RecEvent;
import com.example.newmusic.module.recupgrade.feature.LocalFeatureService;
import com.example.newmusic.module.recupgrade.mapper.RecEventMapper;
import com.example.newmusic.module.recupgrade.mapper.RecFeatureConsistencyMapper;
import com.example.newmusic.module.recupgrade.mapper.RecLogMapper;
import com.example.newmusic.module.recupgrade.mapper.RecMetricDailyMapper;
import com.example.newmusic.module.recupgrade.service.NewSongService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 推荐离线任务（FR-24-011）。
 *
 * <ul>
 *   <li>00:20 聚合昨日 {@code t_rec_event} → {@code t_rec_metric_daily}；</li>
 *   <li>04:40 清理 7 天前的推荐日志与埋点；</li>
 *   <li>每 10 分钟评估新歌扶持效果，完成率不达标的当日停止；</li>
 *   <li>05:10 特征一致性抽检并落库。</li>
 * </ul>
 *
 * <p>线上调度平台是 XXL-Job；这里保留 {@code @Scheduled} 是为了本机可验证，
 * 业务逻辑都在 service 里，迁移过来即可。
 */
@Slf4j
@Component
public class RecJob {

    private final RecUpgradeProperties properties;
    private final RecEventMapper eventMapper;
    private final RecLogMapper logMapper;
    private final RecMetricDailyMapper metricMapper;
    private final RecFeatureConsistencyMapper consistencyMapper;
    private final NewSongService newSongService;
    private final LocalFeatureService featureService;

    public RecJob(RecUpgradeProperties properties, RecEventMapper eventMapper,
                  RecLogMapper logMapper, RecMetricDailyMapper metricMapper,
                  RecFeatureConsistencyMapper consistencyMapper,
                  NewSongService newSongService, LocalFeatureService featureService) {
        this.properties = properties;
        this.eventMapper = eventMapper;
        this.logMapper = logMapper;
        this.metricMapper = metricMapper;
        this.consistencyMapper = consistencyMapper;
        this.newSongService = newSongService;
        this.featureService = featureService;
    }

    @Scheduled(cron = "${newmusic.rec-upgrade.daily-aggregate-cron:0 20 0 * * ?}")
    public void aggregateDaily() {
        if (!properties.isJobsEnabled()) {
            return;
        }
        LocalDate date = LocalDate.now().minusDays(1);
        try {
            int rows = aggregate(date);
            log.info("推荐日报聚合完成 date={} groups={}", date, rows);
        } catch (RuntimeException e) {
            log.error("推荐日报聚合失败 date={}", date, e);
        }
    }

    /** 可被后台手动触发：聚合指定日期。 */
    public int aggregate(LocalDate date) {
        LocalDateTime since = date.atStartOfDay();
        LocalDateTime until = since.plusDays(1);
        Map<String, DailyAccumulator> groups = new LinkedHashMap<>();
        for (RecEventMapper.EventCountRow row : eventMapper.selectDailyCounts(since, until)) {
            String key = (row.getScene() == null ? "" : row.getScene()) + '\u0001'
                    + (row.getExperimentKey() == null ? "" : row.getExperimentKey()) + '\u0001'
                    + (row.getGroupName() == null ? "" : row.getGroupName());
            DailyAccumulator acc = groups.computeIfAbsent(key, k -> new DailyAccumulator(row));
            acc.add(row);
        }
        int written = 0;
        for (DailyAccumulator acc : groups.values()) {
            upsert(date, acc);
            written++;
        }
        return written;
    }

    private void upsert(LocalDate date, DailyAccumulator acc) {
        RecMetricDaily existing = metricMapper.selectOne(Wrappers.<RecMetricDaily>lambdaQuery()
                .eq(RecMetricDaily::getStatDate, date)
                .eq(RecMetricDaily::getScene, acc.scene)
                .eq(RecMetricDaily::getExperimentKey, acc.experimentKey)
                .eq(RecMetricDaily::getGroupName, acc.groupName)
                .last("LIMIT 1"));
        RecMetricDaily row = existing == null ? new RecMetricDaily() : existing;
        row.setStatDate(date);
        row.setScene(acc.scene);
        row.setExperimentKey(acc.experimentKey);
        row.setGroupName(acc.groupName);
        row.setExposureCnt(acc.impression);
        row.setClickCnt(acc.click);
        row.setPlayCnt(acc.play);
        row.setValidPlayCnt(acc.validPlay);
        row.setCompleteCnt(acc.complete);
        row.setLikeCnt(acc.like);
        row.setDislikeCnt(acc.dislike);
        row.setSkipCnt(acc.skip);
        row.setUniqueUsers(acc.uniqueUsers);
        long base = acc.impression > 0 ? acc.impression : acc.play;
        row.setCtr(decimal(base <= 0 ? 0 : acc.click / (double) base));
        row.setCompletionRate(decimal(acc.play <= 0 ? 0 : acc.complete / (double) acc.play));
        row.setAvgValidPlay(decimal(acc.validPlayAvg));
        if (row.getLongtailRatio() == null) {
            row.setLongtailRatio(BigDecimal.ZERO);
        }
        if (row.getDiversityScore() == null) {
            row.setDiversityScore(BigDecimal.ZERO);
        }
        if (existing == null) {
            row.setCreateTime(LocalDateTime.now());
            metricMapper.insert(row);
        } else {
            row.setUpdateTime(LocalDateTime.now());
            metricMapper.updateById(row);
        }
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    @Scheduled(cron = "${newmusic.rec-upgrade.log-cleanup-cron:0 40 4 * * ?}")
    public void cleanup() {
        if (!properties.isJobsEnabled()) {
            return;
        }
        LocalDateTime before = LocalDateTime.now().minusDays(properties.getLogRetentionDays());
        try {
            int logs = logMapper.delete(Wrappers.<RecLog>lambdaQuery().lt(RecLog::getCreateTime, before));
            int events = eventMapper.delete(Wrappers.<RecEvent>lambdaQuery().lt(RecEvent::getCreateTime, before));
            log.info("推荐日志清理完成 logs={} events={} before={}", logs, events, before);
        } catch (RuntimeException e) {
            log.error("推荐日志清理失败", e);
        }
    }

    @Scheduled(cron = "${newmusic.rec-upgrade.new-song-evaluate-cron:0 0/10 * * * ?}")
    public void evaluateNewSong() {
        if (!properties.isJobsEnabled()) {
            return;
        }
        try {
            int stopped = newSongService.evaluateAndStop();
            if (stopped > 0) {
                log.info("新歌扶持评估：停止 {} 首", stopped);
            }
        } catch (RuntimeException e) {
            log.warn("新歌扶持评估失败：{}", e.toString());
        }
    }

    @Scheduled(cron = "${newmusic.rec-upgrade.feature-check-cron:0 10 5 * * ?}")
    public void checkFeatureConsistency() {
        if (!properties.isJobsEnabled()) {
            return;
        }
        try {
            Map<String, Object> report = featureService.consistencySample(200);
            RecFeatureConsistency row = new RecFeatureConsistency();
            row.setCheckDate(LocalDate.now());
            row.setFeatureName(String.valueOf(report.get("feature")));
            row.setSampleSize(toInt(report.get("sampleSize")));
            row.setOfflineMean(toDecimal(report.get("offlineMean")));
            row.setOnlineMean(toDecimal(report.get("onlineMean")));
            row.setDeviation(toDecimal(report.get("deviation")));
            row.setPassed(Boolean.TRUE.equals(report.get("passed")) ? 1 : 0);
            row.setCreateTime(LocalDateTime.now());
            consistencyMapper.insert(row);
            if (!Boolean.TRUE.equals(report.get("passed"))) {
                log.warn("特征一致性超阈值：{}", report);
            }
        } catch (RuntimeException e) {
            log.warn("特征一致性校验失败：{}", e.toString());
        }
    }

    private int toInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private BigDecimal toDecimal(Object value) {
        double v = value instanceof Number ? ((Number) value).doubleValue() : 0;
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }

    /** 一个 (scene, experiment, group) 的累加器。 */
    private static class DailyAccumulator {
        private final String scene;
        private final String experimentKey;
        private final String groupName;
        private long impression;
        private long click;
        private long play;
        private long validPlay;
        private long complete;
        private long like;
        private long dislike;
        private long skip;
        private long uniqueUsers;
        private double validPlayAvg;

        DailyAccumulator(RecEventMapper.EventCountRow first) {
            this.scene = first.getScene() == null ? "" : first.getScene();
            this.experimentKey = first.getExperimentKey() == null ? "" : first.getExperimentKey();
            this.groupName = first.getGroupName() == null ? "" : first.getGroupName();
        }

        void add(RecEventMapper.EventCountRow row) {
            long count = row.getCnt() == null ? 0 : row.getCnt();
            String type = row.getEventType() == null ? "" : row.getEventType();
            switch (type) {
                case RecEvent.IMPRESSION:
                    impression += count;
                    break;
                case RecEvent.CLICK:
                    click += count;
                    break;
                case RecEvent.PLAY:
                    play += count;
                    break;
                case RecEvent.VALID_PLAY:
                    validPlay += count;
                    if (row.getAvgValue() != null) {
                        validPlayAvg = row.getAvgValue();
                    }
                    break;
                case RecEvent.COMPLETE:
                    complete += count;
                    break;
                case RecEvent.LIKE:
                    like += count;
                    break;
                case RecEvent.DISLIKE:
                    dislike += count;
                    break;
                case RecEvent.SKIP:
                    skip += count;
                    break;
                default:
                    break;
            }
            // COUNT(DISTINCT user_id) 是按事件类型分开算的，取最大值作为组内去重用户数的下界
            if (row.getUserCnt() != null) {
                uniqueUsers = Math.max(uniqueUsers, row.getUserCnt());
            }
        }
    }
}
