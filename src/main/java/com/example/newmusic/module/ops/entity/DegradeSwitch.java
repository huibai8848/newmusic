package com.example.newmusic.module.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 降级开关（FR-28-003）。{@code enabled=1} 表示功能正常。 */
@Data
@TableName("t_degrade_switch")
public class DegradeSwitch implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "switch_key", type = IdType.INPUT)
    private String switchKey;

    private String switchName;
    private Integer enabled;
    /** 达到该降级等级时关闭；99 = 永不关闭 */
    private Integer degradeLevel;
    private String description;
    private Long operatorId;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
