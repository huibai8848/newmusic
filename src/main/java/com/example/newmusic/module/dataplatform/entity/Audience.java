package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 人群圈选（BR-26-040 / BR-26-044 精度保护）。 */
@Data
@TableName("t_audience")
public class Audience {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    /** 圈选条件 JSON */
    private String conditions;
    private Integer userCount;
    /** 仅 >=100 人时保存；<100 人为 NULL，避免去匿名化 */
    private String userIds;
    /** 0 计算中 / 1 完成 / 2 失败 */
    private Integer status;
    private Long creatorId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
