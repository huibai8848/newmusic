package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 脏数据隔离（BR-26-019）。 */
@Data
@TableName("t_track_dirty")
public class TrackDirty {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String eventId;
    private String eventName;
    /** 原始事件 JSON，便于人工排查 */
    private String raw;
    private String reason;
    private LocalDateTime createTime;
}
