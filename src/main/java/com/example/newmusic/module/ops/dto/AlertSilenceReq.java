package com.example.newmusic.module.ops.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/** POST /api/v1/admin/alerts/silences */
@Data
public class AlertSilenceReq {

    /** 为空表示按级别静默 */
    private String ruleName;

    /** P0 / P1 / P2 / P3；与 ruleName 至少填一个 */
    private String severity;

    @NotNull(message = "durationMinutes 不能为空")
    @Min(value = 1, message = "静默时长不能小于 1 分钟")
    @Max(value = 1440, message = "静默时长不能超过 24 小时")
    private Integer durationMinutes;

    private String reason;
}
