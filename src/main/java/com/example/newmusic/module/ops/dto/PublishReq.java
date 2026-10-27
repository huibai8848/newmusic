package com.example.newmusic.module.ops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** POST /api/v1/admin/publish */
@Data
public class PublishReq {

    @NotBlank(message = "version 不能为空")
    @Size(max = 64, message = "version 不能超过 64 字")
    private String version;

    private String service = "newmusic-api";

    private String environment = "prod";

    /** FULL / GRAY / CANARY / ROLLBACK */
    private String publishType = "GRAY";

    @Size(max = 64, message = "gitCommit 不能超过 64 字")
    private String gitCommit;

    @Size(max = 500, message = "remark 不能超过 500 字")
    private String remark;
}
