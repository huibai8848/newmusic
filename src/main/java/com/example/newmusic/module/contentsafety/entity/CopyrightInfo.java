package com.example.newmusic.module.contentsafety.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_copyright")
public class CopyrightInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_VALID = 0;
    public static final int STATUS_EXPIRING = 1;
    public static final int STATUS_EXPIRED = 2;
    public static final int STATUS_OFFLINE = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long songId;
    private String copyrightOwner;
    private String licenseType;
    private String licenseRegion;
    private LocalDate licenseStart;
    private LocalDate licenseEnd;
    private String licenseScope;
    private String contractNo;
    private String contractFile;
    private Integer status;
    private Long operatorId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
