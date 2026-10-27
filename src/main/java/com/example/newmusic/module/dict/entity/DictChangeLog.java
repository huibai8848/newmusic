package com.example.newmusic.module.dict.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 词典变更历史（模块 11，BR-20-068）。
 *
 * <p>本表只追加、不修改；{@code beforeValue}/{@code afterValue} 存 JSON 快照，
 * 回滚时由运营按快照人工确认（PRD 只要求「可回滚」，没有要求一键回滚）。
 */
@Data
@TableName("t_dict_change_log")
@ApiModel("词典变更历史")
public class DictChangeLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String dictType;

    private String changeType;

    private Long targetId;

    private String beforeValue;

    private String afterValue;

    private Long operatorId;

    private String operatorName;

    private String remark;

    private String result;

    private LocalDateTime createTime;
}
