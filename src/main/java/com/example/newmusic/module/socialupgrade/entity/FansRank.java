package com.example.newmusic.module.socialupgrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 粉丝榜快照（FR-25-011）。 */
@Data
@TableName("t_fans_rank")
public class FansRank implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Integer rankType;
    private String period;
    private LocalDate statDate;
    private String rankData;
    private LocalDateTime createTime;
}
