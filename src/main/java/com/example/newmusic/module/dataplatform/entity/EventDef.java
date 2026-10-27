package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 埋点定义（PRD 26 FR-26-001，先文档后埋点）。 */
@Data
@TableName("t_event_def")
public class EventDef {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String eventName;
    private String eventCnName;
    private String domain;
    private String triggerDesc;
    /** 业务属性 JSON Schema 文本 */
    private String properties;
    /** 1 核心 / 2 重要 / 3 辅助 */
    private Integer priority;
    private Integer isSampled;
    private BigDecimal sampleRate;
    private String owner;
    /** 埋点定义版本（只增不减，与乐观锁无关） */
    private String version;
    /** 0 草稿 / 1 生效 / 2 废弃 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
