package com.example.newmusic.module.ops.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;

/** 修改限流规则（PUT /api/v1/admin/rate-limits/{resource}）。 */
@Data
public class RateLimitRuleUpdateReq {

    @Min(value = 1, message = "阈值至少为 1")
    @Max(value = 10000000, message = "阈值过大")
    private Integer threshold;

    @Min(value = 1, message = "窗口至少 1 秒")
    @Max(value = 86400, message = "窗口最长 24 小时")
    private Integer windowSeconds;

    @Pattern(regexp = "GLOBAL|IP|USER", message = "维度只能是 GLOBAL/IP/USER")
    private String dimension;

    @Pattern(regexp = "GET|POST|PUT|DELETE|PATCH|", message = "HTTP 方法不合法")
    private String httpMethod;

    private Integer enabled;

    private String whitelistIps;

    private String description;
}
