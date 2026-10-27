package com.example.newmusic.module.contentsafety.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("t_user_restriction")
public class UserRestriction implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int TYPE_COMMENT = 1;
    public static final int TYPE_MUTE = 2;
    public static final int TYPE_BAN = 3;
    public static final int TYPE_PERMANENT_BAN = 4;
    public static final int TYPE_UPLOAD = 5;

    public static final int STATUS_RELEASED = 0;
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_EXPIRED = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private Integer type;
    private Integer level;
    private String reason;
    private String reasonCategory;
    private String relatedContentType;
    private Long relatedContentId;
    private Long relatedReportId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;
    private Long operatorId;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
