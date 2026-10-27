package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** DWS 用户日汇总（按天分区、重跑幂等）。 */
@Data
@TableName("t_dws_user_daily")
public class DwsUserDaily {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private LocalDate dt;
    private Long userId;
    private Integer playCnt;
    private Integer validPlayCnt;
    private Long playDuration;
    private Integer distinctSongCnt;
    private Integer likeCnt;
    private Integer commentCnt;
    private Integer followCnt;
    private Integer postCnt;
    private Integer roomCnt;
    private Integer searchCnt;
    private Integer recommendClickCnt;
    private Integer recommendExposureCnt;
    private Integer sessionCnt;
    private LocalDateTime createTime;
}
