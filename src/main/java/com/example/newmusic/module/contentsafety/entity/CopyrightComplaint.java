package com.example.newmusic.module.contentsafety.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("t_copyright_complaint")
public class CopyrightComplaint implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PROCESSING = 1;
    public static final int STATUS_TAKEN_DOWN = 2;
    public static final int STATUS_REJECTED = 3;
    public static final int STATUS_COUNTER = 4;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String complaintNo;
    private String complainantName;
    private String complainantType;
    private String contactEncrypted;
    private String proofFiles;
    private String songIds;
    private String description;
    private Integer statement;
    private Integer status;
    private String result;
    private Long operatorId;
    private LocalDateTime handleTime;
    private Integer uploaderNotified;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
