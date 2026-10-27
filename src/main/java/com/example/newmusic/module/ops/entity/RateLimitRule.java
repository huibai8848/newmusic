package com.example.newmusic.module.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 接口限流规则（FR-28-001）。规则热更新，见 {@code RateLimitRuleService}。 */
@Data
@TableName("t_ops_rate_limit_rule")
public class RateLimitRule implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String DIM_GLOBAL = "GLOBAL";
    public static final String DIM_IP = "IP";
    public static final String DIM_USER = "USER";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String resource;
    /** URI 前缀；最长匹配优先 */
    private String matchPattern;
    /** 为空表示全部方法 */
    private String httpMethod;
    private String dimension;
    private Integer threshold;
    private Integer windowSeconds;
    private Integer enabled;
    private String whitelistIps;
    private String description;
    private Long operatorId;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
