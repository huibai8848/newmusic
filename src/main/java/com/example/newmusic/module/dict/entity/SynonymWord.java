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
 * 同义词组的一行（模块 11）。
 *
 * <p>表结构是「一行一个词」而不是「一行一个组 + 逗号串」：后者无法建唯一索引，
 * 也无法回答「周董是否已经在别的组里」这类冲突校验。
 */
@Data
@TableName("t_synonym")
@ApiModel("同义词")
public class SynonymWord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @ApiModelProperty("组号，同组共享")
    private Integer groupNo;

    private String word;

    @ApiModelProperty("1 主词，查询改写时优先使用")
    private Integer isPrimary;

    @ApiModelProperty("EQUIVALENT 等价 / ONE_WAY 单向")
    private String synonymType;

    @ApiModelProperty("ARTIST / GENRE / LANGUAGE / ERA / SCENE / EMOTION / OTHER")
    private String category;

    private Integer enabled;

    private Integer sortOrder;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
