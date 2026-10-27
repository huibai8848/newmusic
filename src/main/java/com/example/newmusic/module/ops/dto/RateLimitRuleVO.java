package com.example.newmusic.module.ops.dto;

import com.example.newmusic.module.ops.entity.RateLimitRule;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 限流规则 + 本进程运行态计数（AC-28-001-1~8 的观测面）。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RateLimitRuleVO extends RateLimitRule {

    private static final long serialVersionUID = 1L;

    private long blockedTotal;
    private long allowedTotal;
    private boolean redisAvailable;
    private boolean localFallbackEnabled;
}
