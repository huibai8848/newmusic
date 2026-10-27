package com.example.newmusic.module.admin.vo;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
@ApiModel("运营看板")
public class DashboardVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private long totalUsers;
    private long newUsersToday;
    private long activeUsersToday;
    private long totalSongs;
    private long onlineSongs;
    private long pendingAuditTasks;
    private long escalatedAuditTasks;
    private long pendingReports;
    private long highPriorityReports;
    private long activeRooms;
    private long totalRooms;
    private long todayPlayCount;
    private long todayNewPlaylists;

    /** 近 7 天新增用户，按日期升序。 */
    private List<TrendPoint> userTrend = new ArrayList<>();
    /** 近 7 天播放量。 */
    private List<TrendPoint> playTrend = new ArrayList<>();

    @Data
    public static class TrendPoint implements Serializable {
        private static final long serialVersionUID = 1L;

        private String date;
        private long value;

        public TrendPoint() {
        }

        public TrendPoint(String date, long value) {
            this.date = date;
            this.value = value;
        }
    }
}
