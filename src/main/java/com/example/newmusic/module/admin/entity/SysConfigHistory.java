package com.example.newmusic.module.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("t_sys_config_history")
public class SysConfigHistory implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String configKey;
    private String oldValue;
    private String newValue;
    private Integer valueType;
    private String changeType;
    private Long changedBy;
    private String changedByName;
    private String remark;
    private LocalDateTime createTime;
}
