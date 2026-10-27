package com.example.newmusic.module.socialupgrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 亲密度（FR-25-011）。 */
@Data
@TableName("t_user_relation_score")
public class UserRelationScore implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long targetUserId;
    private Integer score;
    private Integer level;
    private LocalDateTime lastInteractTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
