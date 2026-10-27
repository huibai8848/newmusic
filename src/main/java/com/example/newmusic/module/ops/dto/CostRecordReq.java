package com.example.newmusic.module.ops.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/** POST /api/v1/admin/costs/records（手工补录/演示用） */
@Data
public class CostRecordReq {

    /** AI / STORAGE / CDN / DB / CACHE / MQ / SEARCH / MONITOR / OTHER */
    @NotBlank(message = "category 不能为空")
    private String category;

    @NotBlank(message = "service 不能为空")
    @Size(max = 64, message = "service 不能超过 64 字")
    private String service;

    @NotNull(message = "amountCent 不能为空")
    @Min(value = 0, message = "金额不能为负")
    private Long amountCent;

    @Min(value = 0, message = "预算不能为负")
    private Long budgetCent = 0L;

    @Size(max = 300, message = "detail 不能超过 300 字")
    private String detail;
}
