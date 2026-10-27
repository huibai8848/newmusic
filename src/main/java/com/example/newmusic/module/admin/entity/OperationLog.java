package com.example.newmusic.module.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台操作日志。追加型表：没有 update / delete 列，也不继承 BaseEntity。
 */
@Data
@TableName("t_operation_log")
@ApiModel("后台操作日志")
public class OperationLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long operatorId;
    private String operatorName;
    private String opType;
    private String targetType;
    private String targetId;
    private String description;
    private String beforeSnapshot;
    private String afterSnapshot;
    private String ip;
    private String userAgent;

    /** 1成功 0失败 */
    private Integer result;
    private String errorMsg;
    private String traceId;
    private LocalDateTime createTime;
}
