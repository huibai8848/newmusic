package com.example.newmusic.module.recupgrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推荐配置（版本化）。
 *
 * <p>刻意不继承 {@code BaseEntity}：这里的 {@code version} 是**配置版本号**
 * （1、2、3…），不是乐观锁版本；继承后两者会抢同一个列名。
 */
@Data
@TableName("t_rec_config")
public class RecConfig {

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_HISTORY = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String scene;
    private String configType;
    private String configJson;
    private Integer version;
    private Integer status;
    private Long operatorId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
