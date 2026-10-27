package com.example.newmusic.module.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 发布记录（FR-28-009）。本机为记录模式，不执行真实部署。 */
@Data
@TableName("t_publish_record")
public class PublishRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_ROLLED_BACK = "ROLLED_BACK";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String version;
    private String service;
    private String environment;
    private String publishType;
    private String gitCommit;
    private String status;
    private String publisher;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String remark;
    private String rollbackVersion;
    private LocalDateTime createTime;
}
