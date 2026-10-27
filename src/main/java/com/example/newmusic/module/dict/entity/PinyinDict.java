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
 * 拼音多音字词典（模块 11）。用于修正 pinyin4j「多音字取第一个读音」的错误，
 * 例如「重庆」默认会物化成 {@code zhongqing}，本表登记为 {@code chongqing}。
 */
@Data
@TableName("t_pinyin_dict")
@ApiModel("拼音词典")
public class PinyinDict implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String word;

    @ApiModelProperty("无声调小写拼音，如 chongqing")
    private String pinyin;

    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
