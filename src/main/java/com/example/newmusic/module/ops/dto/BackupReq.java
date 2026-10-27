package com.example.newmusic.module.ops.dto;

import lombok.Data;

import javax.validation.constraints.Size;

/** POST /api/v1/admin/backups */
@Data
public class BackupReq {

    /** FULL / INCREMENTAL */
    private String backupType = "FULL";

    @Size(max = 300, message = "message 不能超过 300 字")
    private String message;
}
