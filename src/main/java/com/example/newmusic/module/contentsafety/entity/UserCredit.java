package com.example.newmusic.module.contentsafety.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("t_user_credit")
public class UserCredit implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int INITIAL_SCORE = 100;

    @TableId(type = IdType.INPUT)
    private Long userId;

    private Integer score;
    /** 列名是 violation_count_90d；MyBatis-Plus 默认驼峰转换会得到 violation_count90d。 */
    @TableField("violation_count_90d")
    private Integer violationCount90d;
    private LocalDateTime lastViolationTime;
    private LocalDateTime lastRecoverTime;
    private LocalDateTime updateTime;
}
