package com.example.newmusic.module.recupgrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推荐埋点事件。
 *
 * <p>实验指标只从这张表聚合，保证「实验指标与数据平台同一口径」（BR-24-092）。
 * {@code experimentKey} / {@code groupName} 用空串而不是 NULL：MySQL 唯一索引
 * 对 NULL 不去重，日报 upsert 会写出多行。
 */
@Data
@TableName("t_rec_event")
public class RecEvent {

    public static final String IMPRESSION = "IMPRESSION";
    public static final String CLICK = "CLICK";
    public static final String PLAY = "PLAY";
    public static final String VALID_PLAY = "VALID_PLAY";
    public static final String COMPLETE = "COMPLETE";
    public static final String SKIP = "SKIP";
    public static final String LIKE = "LIKE";
    public static final String DISLIKE = "DISLIKE";
    public static final String EXPLORE_IMPRESSION = "EXPLORE_IMPRESSION";
    public static final String NEW_SONG_EXPOSURE = "NEW_SONG_EXPOSURE";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String traceId;
    private Long userId;
    private String deviceId;
    private String scene;
    private String eventType;
    private String experimentKey;
    private String groupName;
    private Long songId;
    private Integer position;
    private Integer playDuration;
    private String userType;
    private String deviceType;
    private Integer hourOfDay;
    private LocalDateTime createTime;
}
