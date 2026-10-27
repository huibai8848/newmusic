package com.example.newmusic.module.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 降级等级变更记录（AC-28-114：降级操作有权限与日志）。 */
@Data
@TableName("t_degrade_event")
public class DegradeEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Integer fromLevel;
    private Integer toLevel;
    private Integer switchesChanged;
    private String reason;
    private Long operatorId;
    private String operatorName;
    private LocalDateTime createTime;
}
