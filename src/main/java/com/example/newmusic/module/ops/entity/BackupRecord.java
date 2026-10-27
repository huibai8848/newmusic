package com.example.newmusic.module.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 备份清单记录（FR-28-012）。本机不做 mysqldump，只做清单 + 校验和。 */
@Data
@TableName("t_backup_record")
public class BackupRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String backupType;
    private String status;
    private Long sizeBytes;
    private String location;
    private String checksum;
    private String verifyStatus;
    private Integer tableCount;
    private Long rowCount;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long operatorId;
    private String message;
    private LocalDateTime createTime;
}
