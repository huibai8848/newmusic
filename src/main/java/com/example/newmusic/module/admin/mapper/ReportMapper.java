package com.example.newmusic.module.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.admin.entity.Report;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ReportMapper extends BaseMapper<Report> {

    /** 条件更新：已处理的举报不能被二次处理（幂等 + 防并发）。 */
    @Update("UPDATE t_report SET status = #{status}, handled_by = #{adminId}, handled_at = NOW(3), "
            + "handle_result = #{result}, handle_reason = #{reason}, action_taken = #{action}, "
            // handle_type 是 TINYINT（1 成立 / 2 不成立 / 3 待补充），与 status 同值；
            // 这里曾误写成 action，字符串写进 TINYINT 会 1366 直接 99001。
            + "handle_type = #{status}, version = version + 1 "
            + "WHERE id = #{reportId} AND status IN (0, 3)")
    int handle(@Param("reportId") Long reportId, @Param("adminId") Long adminId,
               @Param("status") int status, @Param("result") String result,
               @Param("reason") String reason, @Param("action") String action);

    /** 合并同目标同原因的其它待处理举报。 */
    @Update("UPDATE t_report SET status = 4, merged_into_id = #{mainId}, version = version + 1 "
            + "WHERE target_type = #{targetType} AND target_id = #{targetId} "
            + "AND status = 0 AND id <> #{mainId}")
    int mergeSiblings(@Param("mainId") Long mainId, @Param("targetType") String targetType,
                      @Param("targetId") String targetId);

    /** 已合并到主举报的记录（模块 10 D-10：主举报处置后要通知这些举报人）。 */
    @Select("SELECT * FROM t_report WHERE merged_into_id = #{mainId} AND status = 4")
    List<Report> selectMerged(@Param("mainId") Long mainId);

    /** 模块 18：合并举报人数 + 记录举报人（24 小时内同一内容）。 */
    @Update("UPDATE t_report SET report_count = report_count + 1, "
            + "reporter_ids = JSON_ARRAY_APPEND(IF(reporter_ids IS NULL OR reporter_ids = '', '[]', reporter_ids), "
            + "'$', #{reporterId}), version = version + 1 WHERE id = #{mainId}")
    int mergeInto(@Param("mainId") Long mainId, @Param("reporterId") Long reporterId);

    /** 举报人数达到阈值时提升优先级并缩短 SLA。 */
    @Update("UPDATE t_report SET priority = #{priority}, sla_deadline = #{deadline}, "
            + "version = version + 1 WHERE id = #{id} AND status = 0")
    int raisePriority(@Param("id") Long id, @Param("priority") int priority,
                      @Param("deadline") java.time.LocalDateTime deadline);

    @Update("UPDATE t_report SET auto_hidden = 1, version = version + 1 "
            + "WHERE id = #{id} AND auto_hidden = 0")
    int markAutoHidden(@Param("id") Long id);

    /** 监管转办：优先级提到最高并把 SLA 压到 2 小时。 */
    @Update("UPDATE t_report SET is_regulator = 1, priority = 1, "
            + "sla_deadline = #{deadline}, version = version + 1 WHERE id = #{id} AND status = 0")
    int markRegulator(@Param("id") Long id, @Param("deadline") java.time.LocalDateTime deadline);

    @Select("SELECT * FROM t_report WHERE status = 0 AND sla_deadline IS NOT NULL "
            + "AND sla_deadline < NOW(3) ORDER BY priority ASC, sla_deadline ASC LIMIT 500")
    List<Report> selectSlaOverdue();
}
