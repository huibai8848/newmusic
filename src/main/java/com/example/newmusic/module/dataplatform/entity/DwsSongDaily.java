package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** DWS 歌曲日汇总。 */
@Data
@TableName("t_dws_song_daily")
public class DwsSongDaily {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private LocalDate dt;
    private Long songId;
    private Long artistId;
    private Integer playCnt;
    private Integer validPlayCnt;
    private Long playDuration;
    private Integer distinctUserCnt;
    private Integer completeCnt;
    private Integer skipCnt;
    private Integer likeCnt;
    private Integer collectCnt;
    private Integer commentCnt;
    private Integer shareCnt;
    private Integer recommendExposureCnt;
    private Integer recommendClickCnt;
    private LocalDateTime createTime;
}
