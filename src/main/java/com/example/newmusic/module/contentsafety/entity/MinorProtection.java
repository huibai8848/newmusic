package com.example.newmusic.module.contentsafety.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("t_minor_protection")
public class MinorProtection implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private Long userId;

    private Integer isMinor;
    private Integer mode;

    /** BCrypt 哈希，任何接口都不回传。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String modePassword;
    private Integer dailyLimitMinutes;
    private LocalTime disableStart;
    private LocalTime disableEnd;
    private Long guardianUserId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
