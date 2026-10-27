package com.example.newmusic.module.dict.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * IK 停用词（模块 11）。只用于查询分析（{@code search_analyzer}），
 * 不参与索引分析——否则「歌」这类通用词会从倒排索引里消失（BR-20-019）。
 */
@Data
@TableName("t_ik_stopword")
@ApiModel("IK 停用词")
public class IkStopword implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String word;

    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
