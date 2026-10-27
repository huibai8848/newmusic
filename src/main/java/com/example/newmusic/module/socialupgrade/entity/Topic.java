package com.example.newmusic.module.socialupgrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 话题（FR-25-003 / P1 话题页）。 */
@Data
@TableName("t_topic")
public class Topic implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private Integer postCount;
    private Integer viewCount;
    private Integer hotScore;
    private Integer isOfficial;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
