package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 运营报表（FR-26-009）。 */
@Data
@TableName("t_data_report")
public class DataReport {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String reportType;
    private LocalDate reportDate;
    private String title;
    /** 报表内容 JSON */
    private String content;
    /** 0 生成中 / 1 完成 / 2 失败 */
    private Integer status;
    private Integer sentCount;
    private LocalDateTime generatedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
