package com.example.newmusic.module.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.admin.entity.AuditTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AuditTaskMapper extends BaseMapper<AuditTask> {

    /**
     * 领取：只有「待领」才能被领取，条件更新保证并发下只有一个管理员成功。
     * 领取锁 10 分钟；超时由 {@link #releaseExpired()} 自动放回。
     */
    @Update("UPDATE t_audit_task SET status = 1, claimed_by = #{adminId}, claimed_at = NOW(3), "
            + "claim_expire_at = DATE_ADD(NOW(3), INTERVAL 10 MINUTE), version = version + 1 "
            + "WHERE id = #{taskId} AND status = 0")
    int claim(@Param("taskId") Long taskId, @Param("adminId") Long adminId);

    /** 续领（仍由本人持有且未超时）。 */
    @Update("UPDATE t_audit_task SET claim_expire_at = DATE_ADD(NOW(3), INTERVAL 10 MINUTE), "
            + "version = version + 1 WHERE id = #{taskId} AND status = 1 AND claimed_by = #{adminId} "
            + "AND claim_expire_at > NOW(3)")
    int renewClaim(@Param("taskId") Long taskId, @Param("adminId") Long adminId);

    /** 主动释放。 */
    @Update("UPDATE t_audit_task SET status = 0, claimed_by = NULL, claimed_at = NULL, "
            + "claim_expire_at = NULL, version = version + 1 "
            + "WHERE id = #{taskId} AND status = 1 AND claimed_by = #{adminId}")
    int release(@Param("taskId") Long taskId, @Param("adminId") Long adminId);

    /** 定时任务：把过期的领取放回待领池。 */
    @Update("UPDATE t_audit_task SET status = 0, claimed_by = NULL, claimed_at = NULL, "
            + "claim_expire_at = NULL, version = version + 1 "
            + "WHERE status = 1 AND claim_expire_at IS NOT NULL AND claim_expire_at < NOW(3)")
    int releaseExpired();

    /** 处理：带原状态 + 领取人条件，防止两个管理员同时处理。 */
    @Update("UPDATE t_audit_task SET status = #{afterStatus}, handled_by = #{adminId}, handled_at = NOW(3), "
            + "handle_result = #{result}, reject_reason = #{reason}, action_applied = #{actionApplied}, "
            + "version = version + 1 "
            + "WHERE id = #{taskId} AND status IN (0, 1) "
            + "AND (claimed_by IS NULL OR claimed_by = #{adminId}) "
            + "AND (claim_expire_at IS NULL OR claim_expire_at > NOW(3))")
    int handle(@Param("taskId") Long taskId, @Param("adminId") Long adminId,
               @Param("afterStatus") int afterStatus, @Param("result") String result,
               @Param("reason") String reason, @Param("actionApplied") int actionApplied);

    /** 24 小时未处理 → 升级标记（只标一次）。 */
    @Update("UPDATE t_audit_task SET escalated = 1, escalated_at = NOW(3) "
            + "WHERE escalated = 0 AND status IN (0, 1) "
            + "AND create_time < DATE_SUB(NOW(3), INTERVAL 24 HOUR)")
    int escalateOverdue();

    /** 模块 18：超过任务自身 SLA（高优 2 小时/普通 24 小时）→ 升级。 */
    @Update("UPDATE t_audit_task SET escalated = 1, escalated_at = NOW(3) "
            + "WHERE escalated = 0 AND status IN (0, 1) "
            + "AND sla_deadline IS NOT NULL AND sla_deadline < NOW(3)")
    int escalateSlaBreached();
}
