package com.example.newmusic.module.admin.mapper;

import com.example.newmusic.module.admin.vo.DashboardVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 运营看板聚合查询。
 *
 * <p>全部走真实表的 COUNT / GROUP BY，不返回任何演示数据；表名前缀
 * {@code newmusic.} 是共享 MySQL 实例上的显式隔离，避免误读其他项目的库。
 * 每个指标都带 {@code deleted = 0}（审核/举报表没有逻辑删除列，故不加）。
 */
@Mapper
public interface DashboardMapper {

    @Select("SELECT "
            + "(SELECT COUNT(*) FROM newmusic.t_user WHERE deleted = 0) AS totalUsers, "
            + "(SELECT COUNT(*) FROM newmusic.t_user WHERE deleted = 0 AND create_time >= CURDATE()) AS newUsersToday, "
            + "(SELECT COUNT(DISTINCT user_id) FROM newmusic.t_play_history "
            + "   WHERE deleted = 0 AND user_id IS NOT NULL AND play_time >= CURDATE()) AS activeUsersToday, "
            + "(SELECT COUNT(*) FROM newmusic.t_song WHERE deleted = 0) AS totalSongs, "
            + "(SELECT COUNT(*) FROM newmusic.t_song WHERE deleted = 0 AND status = 1) AS onlineSongs, "
            + "(SELECT COUNT(*) FROM newmusic.t_audit_task WHERE status IN (0, 1)) AS pendingAuditTasks, "
            + "(SELECT COUNT(*) FROM newmusic.t_audit_task WHERE status IN (0, 1) AND escalated = 1) AS escalatedAuditTasks, "
            + "(SELECT COUNT(*) FROM newmusic.t_report WHERE status = 0) AS pendingReports, "
            + "(SELECT COUNT(*) FROM newmusic.t_report WHERE status = 0 AND priority = 1) AS highPriorityReports, "
            + "(SELECT COUNT(*) FROM newmusic.t_room WHERE deleted = 0 AND status = 1) AS activeRooms, "
            + "(SELECT COUNT(*) FROM newmusic.t_room WHERE deleted = 0) AS totalRooms, "
            + "(SELECT COUNT(*) FROM newmusic.t_play_history WHERE deleted = 0 AND play_time >= CURDATE()) AS todayPlayCount, "
            + "(SELECT COUNT(*) FROM newmusic.t_playlist WHERE deleted = 0 AND create_time >= CURDATE()) AS todayNewPlaylists")
    DashboardVO summary();

    // GROUP BY / ORDER BY 必须与 SELECT 的表达式逐字一致：生产 MySQL 默认开启
    // only_full_group_by，SELECT DATE_FORMAT(...) 却 GROUP BY DATE(...) 会被直接判为
    // 「非聚合列不在 GROUP BY 中」而不是按日期分组。
    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS date, COUNT(*) AS value "
            + "FROM newmusic.t_user "
            + "WHERE deleted = 0 AND create_time >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) "
            + "GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d') "
            + "ORDER BY DATE_FORMAT(create_time, '%Y-%m-%d')")
    List<DashboardVO.TrendPoint> userTrend();

    @Select("SELECT DATE_FORMAT(play_time, '%Y-%m-%d') AS date, COUNT(*) AS value "
            + "FROM newmusic.t_play_history "
            + "WHERE deleted = 0 AND play_time >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) "
            + "GROUP BY DATE_FORMAT(play_time, '%Y-%m-%d') "
            + "ORDER BY DATE_FORMAT(play_time, '%Y-%m-%d')")
    List<DashboardVO.TrendPoint> playTrend();
}
