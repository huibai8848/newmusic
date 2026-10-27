package com.example.newmusic.module.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 搜索历史（{@code t_search_history}，DDL 照抄 PRD 04 §10，D-47）。
 *
 * <p><b>不继承 {@code BaseEntity}。</b>这张表没有 {@code deleted}、{@code version}、
 * {@code update_by}——它是**只追加的日志**，从来没有「更新」和「逻辑删除」的语义。
 * 继承基类会凭空得到这些字段，MyBatis-Plus 还会据此自动往 SQL 里塞
 * {@code AND deleted = 0}，而那个列不存在，查询直接报错。
 *
 * <p>它是「落库用于分析」的那一份，**不是读取来源**：读走 Redis（§6.3）。
 */
@Data
@TableName("t_search_history")
@ApiModel("搜索历史")
public class SearchHistory implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @ApiModelProperty("用户ID；仅登录用户记录（BR-15-006）")
    private Long userId;

    @ApiModelProperty("关键词")
    private String keyword;

    @ApiModelProperty("1关键词 2AI")
    private Integer searchType;

    @ApiModelProperty("本次结果数")
    private Integer resultCount;

    @ApiModelProperty("是否点击过结果")
    private Integer isClicked;

    private LocalDateTime createTime;
}
