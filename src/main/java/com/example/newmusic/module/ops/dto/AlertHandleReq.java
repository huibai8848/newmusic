package com.example.newmusic.module.ops.dto;

import lombok.Data;

import javax.validation.constraints.Size;

/** POST /api/v1/admin/alerts/records/{id}/handle */
@Data
public class AlertHandleReq {

    @Size(max = 500, message = "处理备注不能超过 500 字")
    private String note;
}
