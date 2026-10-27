package com.example.newmusic.module.ops.dto;

import lombok.Data;

import javax.validation.constraints.Size;

/** POST /api/v1/admin/publish/{id}/rollback */
@Data
public class RollbackReq {

    /** 为空则回滚到最近一次成功发布的版本 */
    @Size(max = 64, message = "targetVersion 不能超过 64 字")
    private String targetVersion;

    @Size(max = 500, message = "reason 不能超过 500 字")
    private String reason;
}
