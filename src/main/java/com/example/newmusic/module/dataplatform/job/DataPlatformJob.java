package com.example.newmusic.module.dataplatform.job;

import com.example.newmusic.module.dataplatform.service.DataQualityService;
import com.example.newmusic.module.dataplatform.service.ProfileTagService;
import com.example.newmusic.module.dataplatform.service.ReconciliationService;
import com.example.newmusic.module.dataplatform.service.ReportService;
import com.example.newmusic.module.dataplatform.service.WarehouseEtlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 数据平台定时任务（FR-26-010 调度）。
 *
 * <p>默认关闭（{@code newmusic.data-platform.jobs-enabled=false}），
 * 验收与本地开发一律走后台的手工触发接口，避免半夜跑批污染共享库。
 * 打开后按 PRD 的顺序：ETL → 画像 → 质量 → 对账 → 报表。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "newmusic.data-platform", name = "jobsEnabled", havingValue = "true")
public class DataPlatformJob {

    private final WarehouseEtlService etlService;
    private final ProfileTagService profileTagService;
    private final DataQualityService qualityService;
    private final ReconciliationService reconciliationService;
    private final ReportService reportService;

    public DataPlatformJob(WarehouseEtlService etlService, ProfileTagService profileTagService,
                           DataQualityService qualityService,
                           ReconciliationService reconciliationService, ReportService reportService) {
        this.etlService = etlService;
        this.profileTagService = profileTagService;
        this.qualityService = qualityService;
        this.reconciliationService = reconciliationService;
        this.reportService = reportService;
    }

    @Scheduled(cron = "${newmusic.data-platform.etl-cron:0 20 0 * * ?}")
    public void etl() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        try {
            etlService.run(yesterday.toString());
        } catch (Exception e) {
            log.error("data-platform etl job failed date={}", yesterday, e);
        }
    }

    @Scheduled(cron = "${newmusic.data-platform.profile-cron:0 40 0 * * ?}")
    public void profile() {
        try {
            profileTagService.recomputeAll(LocalDate.now().minusDays(1).toString());
        } catch (Exception e) {
            log.error("data-platform profile job failed", e);
        }
    }

    @Scheduled(cron = "${newmusic.data-platform.quality-cron:0 10 1 * * ?}")
    public void quality() {
        try {
            qualityService.run(LocalDate.now().minusDays(1).toString());
        } catch (Exception e) {
            log.error("data-platform quality job failed", e);
        }
    }

    @Scheduled(cron = "${newmusic.data-platform.reconciliation-cron:0 40 1 * * ?}")
    public void reconciliation() {
        try {
            reconciliationService.run(LocalDate.now().minusDays(1).toString());
        } catch (Exception e) {
            log.error("data-platform reconciliation job failed", e);
        }
    }

    @Scheduled(cron = "${newmusic.data-platform.report-cron:0 0 7 * * ?}")
    public void report() {
        try {
            reportService.generateAll(LocalDate.now().minusDays(1).toString());
        } catch (Exception e) {
            log.error("data-platform report job failed", e);
        }
    }
}
