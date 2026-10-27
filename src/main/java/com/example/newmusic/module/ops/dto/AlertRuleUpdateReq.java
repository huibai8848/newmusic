package com.example.newmusic.module.ops.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** PUT /api/v1/admin/alerts/rules/{id} */
@Data
public class AlertRuleUpdateReq {

    @NotNull(message = "thresholdValue 不能为空")
    private Double thresholdValue;

    /** GT / GE / LT / LE */
    private String operator;

    @Min(value = 0, message = "durationSeconds 不能小于 0")
    @Max(value = 86400, message = "durationSeconds 不能大于 86400")
    private Integer durationSeconds;

    /** P0 / P1 / P2 / P3 */
    private String severity;

    private Integer enabled;

    @NotBlank(message = "runbookKey 不能为空")
    private String runbookKey;

    private String description;
}
