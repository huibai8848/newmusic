package com.example.newmusic.module.ai.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

/**
 * 预算配置（分）。两个字段都可单独更新；
 * {@code dailyBudgetCent=0} 表示不设上限（仅用于本地联调，生产不建议）。
 */
@Data
public class AiBudgetUpdateReq {

    @Min(value = 0, message = "日预算不能为负数")
    private Long dailyBudgetCent;

    @Min(value = 1, message = "告警阈值 1~100")
    @Max(value = 100, message = "告警阈值 1~100")
    private Integer alertPercent;
}
