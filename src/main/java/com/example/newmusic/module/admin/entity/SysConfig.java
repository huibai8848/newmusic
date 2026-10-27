package com.example.newmusic.module.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * t_sys_config 没有 deleted / version / create_by，故不继承 BaseEntity；
 * 否则 MyBatis-Plus 会把不存在的列拼进 SQL。
 */
@Data
@TableName("t_sys_config")
public class SysConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int TYPE_STRING = 1;
    public static final int TYPE_NUMBER = 2;
    public static final int TYPE_BOOLEAN = 3;
    public static final int TYPE_JSON = 4;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String configKey;
    private String configValue;
    private Integer valueType;
    private String configGroup;
    private String description;
    private Integer isPublic;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Long updateBy;
}
