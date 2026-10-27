package com.example.newmusic.module.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("t_report")
public class Report implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_VALID = 1;
    public static final int STATUS_INVALID = 2;
    public static final int STATUS_NEED_MORE = 3;
    public static final int STATUS_MERGED = 4;

    public static final int PRIORITY_HIGH = 1;
    public static final int PRIORITY_NORMAL = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 模块 18：举报单号 */
    private String reportNo;

    private Long reporterId;
    private String reporterName;
    private String targetType;
    private String targetId;
    private Long targetUserId;
    private String targetName;
    private String reasonType;
    private String reasonCategory;
    private String reason;
    private String reasonDetail;
    private String evidenceUrls;
    private Integer reportCount;
    private String reporterIds;
    private Integer priority;
    private Integer status;

    private Long handledBy;
    private LocalDateTime handledAt;
    private String handleResult;
    /** 1 成立 / 2 不成立 / 3 待补充，与 status 同值（库列为 TINYINT）。 */
    private Integer handleType;
    private Integer isRegulator;
    private LocalDateTime slaDeadline;
    private Integer autoHidden;
    private String handleReason;
    private String actionTaken;
    private Long mergedIntoId;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer version;
}
