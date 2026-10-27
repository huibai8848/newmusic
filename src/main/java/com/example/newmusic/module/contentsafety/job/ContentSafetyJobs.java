package com.example.newmusic.module.contentsafety.job;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.admin.mapper.AuditTaskMapper;
import com.example.newmusic.module.contentsafety.service.CopyrightService;
import com.example.newmusic.module.contentsafety.service.ReportSafetyService;
import com.example.newmusic.module.contentsafety.service.UserRestrictionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 内容安全定时任务。
 *
 * <p>模块 09 的 {@code AdminScheduledJobs} 已经负责「领取超时释放」和「24 小时升级」，
 * 这里不重复；本类只补模块 18 新增的 SLA、限制到期、信用恢复、版权巡检、举报 SLA 补齐。
 *
 * <p>每个任务都用 Redis 短锁 + 条件更新保证多实例幂等；锁只是减少重复执行，
 * 真正的正确性仍依赖 SQL 的 {@code WHERE status = ...} 条件。
 */
@Slf4j
@Component
public class ContentSafetyJobs {

    private final AuditTaskMapper auditTaskMapper;
    private final UserRestrictionService restrictionService;
    private final CopyrightService copyrightService;
    private final ReportSafetyService reportSafetyService;
    private final RedisOps redisOps;

    public ContentSafetyJobs(AuditTaskMapper auditTaskMapper,
                             UserRestrictionService restrictionService,
                             CopyrightService copyrightService,
                             ReportSafetyService reportSafetyService, RedisOps redisOps) {
        this.auditTaskMapper = auditTaskMapper;
        this.restrictionService = restrictionService;
        this.copyrightService = copyrightService;
        this.reportSafetyService = reportSafetyService;
        this.redisOps = redisOps;
    }

    /** 任务自身 SLA 超时升级（高优 2h / 普通 24h）。每 10 分钟。 */
    @Scheduled(fixedDelayString = "${newmusic.content.sla-scan-ms:600000}",
            initialDelayString = "${newmusic.content.sla-initial-delay-ms:90000}")
    public void escalateSlaBreached() {
        runOnce("sla-escalate", Duration.ofMinutes(9), () -> {
            int count = auditTaskMapper.escalateSlaBreached();
            if (count > 0) {
                log.warn("审核任务 SLA 超时升级 count={}", count);
            }
        });
    }

    /** 用户限制到期自动解除。每 5 分钟。 */
    @Scheduled(fixedDelayString = "${newmusic.content.restriction-scan-ms:300000}",
            initialDelayString = "${newmusic.content.restriction-initial-delay-ms:120000}")
    public void releaseExpiredRestrictions() {
        runOnce("restriction-release", Duration.ofMinutes(4), () -> {
            int count = restrictionService.releaseExpired();
            if (count > 0) {
                log.info("到期用户限制已解除 count={}", count);
            }
        });
    }

    /** 信用分自然恢复：每天 00:20，仅处理 30 天无违规的用户。 */
    @Scheduled(cron = "${newmusic.content.credit-recover-cron:0 20 0 * * ?}")
    public void recoverCredit() {
        runOnce("credit-recover", Duration.ofHours(23), () -> {
            int count = restrictionService.recoverMonthly();
            if (count > 0) {
                log.info("信用分自然恢复 count={}", count);
            }
        });
    }

    /** 版权巡检：每天 06:10，发到期提醒并在最后一天自动下架。 */
    @Scheduled(cron = "${newmusic.content.copyright-scan-cron:0 10 6 * * ?}")
    public void checkCopyrightExpiry() {
        runOnce("copyright-expiry", Duration.ofHours(23), () -> {
            int offline = copyrightService.checkExpiry();
            log.info("版权巡检完成 offline={}", offline);
        });
    }

    /** 举报 SLA 补齐 + 超时告警。每小时。 */
    @Scheduled(fixedDelayString = "${newmusic.content.report-sla-scan-ms:3600000}",
            initialDelayString = "${newmusic.content.report-sla-initial-delay-ms:180000}")
    public void scanReportSla() {
        runOnce("report-sla", Duration.ofMinutes(55), () ->
                reportSafetyService.backfillAndCountOverdue());
    }

    private void runOnce(String job, Duration lockTtl, Runnable action) {
        String key = RedisKeys.auditJobLock(job);
        if (!redisOps.setIfAbsent(key, "1", lockTtl)) {
            return;
        }
        try {
            action.run();
        } catch (Exception e) {
            log.warn("内容安全定时任务失败 job={} cause={}", job, e.toString());
        } finally {
            redisOps.delete(key);
        }
    }
}
