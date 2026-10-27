package com.example.newmusic.module.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 热搜词（{@code t_search_hot}，DDL 照抄 PRD 04 §10，D-47）。同样不继承 BaseEntity，理由见 {@link SearchHistory} */
@Data
@TableName("t_search_hot")
@ApiModel("热搜词")
public class SearchHot implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @ApiModelProperty("关键词")
    private String keyword;

    @ApiModelProperty("搜索次数。**只给运营看，接口不返回**（BR-15-034）")
    private Long searchCount;

    @ApiModelProperty("排名")
    private Integer rankNo;

    @ApiModelProperty("是否运营置顶")
    private Integer isTop;

    @ApiModelProperty("统计日期")
    private LocalDate statDate;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
