package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** DWD 播放明细（ODS 埋点清洗并关联歌曲/歌手维度后的宽表）。 */
@Data
@TableName("t_dwd_play_detail")
public class DwdPlayDetail {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String eventId;
    private LocalDate dt;
    private Long userId;
    private String deviceId;
    private Long songId;
    private Long artistId;
    private String genre;
    private String language;
    private Integer playDuration;
    private Integer songDuration;
    private BigDecimal completionRate;
    private Integer isValid;
    private String fromSource;
    private Integer position;
    private String recommendTraceId;
    private String quality;
    private LocalDateTime serverTime;
    private LocalDateTime createTime;
}
