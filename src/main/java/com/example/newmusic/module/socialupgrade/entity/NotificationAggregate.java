package com.example.newmusic.module.socialupgrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 通知聚合记录（FR-25-008）。一条聚合消息对应一条 t_notification。 */
@Data
@TableName("t_notification_aggregate")
public class NotificationAggregate implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String aggregateKey;
    private Long notificationId;
    /** JSON 数组 */
    private String participantIds;
    private Integer count;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
