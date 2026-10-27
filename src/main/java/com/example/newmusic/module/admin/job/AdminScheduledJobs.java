package com.example.newmusic.module.admin.job;

import com.example.newmusic.module.admin.service.AdminUserManageService;
import com.example.newmusic.module.admin.service.AuditService;
import com.example.newmusic.module.admin.service.SongAuditTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后台定时任务。
 *
 * <p>三件事都靠「条件更新」实现幂等，多实例同时跑不会把状态推错；
 * 每个任务独立 try/catch，一个任务失败不影响其他任务。
 *
 * <p>没有引入 XXL-Job（与模块 06/08 的处理一致），用 {@code @Scheduled} 先跑通；
 * 任务本身只调用 Service，将来迁移调度平台不需要改业务代码。
 */
@Slf4j
@Component
public class AdminScheduledJobs {

    private final AuditService auditService;
    private final AdminUserManageService userManageService;
    private final SongAuditTaskService songAuditTaskService;

    public AdminScheduledJobs(AuditService auditService, AdminUserManageService userManageService,
                              SongAuditTaskService songAuditTaskService) {
        this.auditService = auditService;
        this.userManageService = userManageService;
        this.songAuditTaskService = songAuditTaskService;
    }

    /** 释放超过 10 分钟仍未处理的领取锁，让任务回到待领池。 */
    @Scheduled(fixedDelayString = "${newmusic.admin.audit-claim-release-ms:300000}",
            initialDelayString = "${newmusic.admin.audit-claim-initial-delay-ms:60000}")
    public void releaseExpiredClaims() {
        try {
            int released = auditService.releaseExpiredClaims();
            if (released > 0) {
                log.info("已释放超时审核领取锁 count={}", released);
            }
        } catch (Exception e) {
            log.warn("释放超时审核领取锁失败 cause={}", e.toString());
        }
    }

    /** 超过 24 小时未处理的审核任务升级优先级（每小时）。 */
    @Scheduled(fixedDelayString = "${newmusic.admin.audit-escalate-ms:3600000}",
            initialDelayString = "${newmusic.admin.audit-escalate-initial-delay-ms:120000}")
    public void escalateOverdueTasks() {
        try {
            int escalated = auditService.escalateOverdueTasks();
            if (escalated > 0) {
                log.info("已升级超时审核任务 count={}", escalated);
            }
        } catch (Exception e) {
            log.warn("升级超时审核任务失败 cause={}", e.toString());
        }
    }

    /**
     * 歌曲审核任务对账（启动回填之外的持续兜底）。
     *
     * <p>覆盖两种启动回填救不了的情况：① 歌曲事务已提交，但进程在
     * {@code afterCommit} 建任务前退出；② 歌曲被删除/审核通过后，旧任务仍留在审核台。
     * 默认 5 分钟一次，单次最多处理 500 条，失败只记日志。
     */
    @Scheduled(fixedDelayString = "${newmusic.admin.song-audit-scan-ms:300000}",
            initialDelayString = "${newmusic.admin.song-audit-initial-delay-ms:180000}")
    public void reconcileSongAuditTasks() {
        try {
            songAuditTaskService.reconcile(500);
        } catch (Exception e) {
            log.warn("歌曲审核任务对账失败 cause={}", e.toString());
        }
    }

    /** 封禁到期自动解封。封禁时已经给 Redis 标记设了同样的 TTL，这里是 DB 侧的兜底。 */
    @Scheduled(fixedDelayString = "${newmusic.admin.unban-scan-ms:300000}",
            initialDelayString = "${newmusic.admin.unban-initial-delay-ms:90000}")
    public void unbanExpired() {
        try {
            int unbanned = userManageService.unbanExpired();
            if (unbanned > 0) {
                log.info("已自动解封到期用户 count={}", unbanned);
            }
        } catch (Exception e) {
            log.warn("自动解封到期用户失败 cause={}", e.toString());
        }
    }
}
