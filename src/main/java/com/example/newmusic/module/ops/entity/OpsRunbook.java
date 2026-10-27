package com.example.newmusic.module.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 应急预案（≥7 个场景，AC-28-179）。 */
@Data
@TableName("t_ops_runbook")
public class OpsRunbook implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String runbookKey;
    private String title;
    private String severity;
    private String symptom;
    private String steps;
    private String owner;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
