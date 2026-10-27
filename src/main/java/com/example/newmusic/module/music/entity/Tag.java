package com.example.newmusic.module.music.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tag")
@ApiModel("标签")
public class Tag extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("标签名")
    private String name;

    @ApiModelProperty("1风格 2场景 3情绪 4年代 5语种 6自定义")
    private Integer tagType;

    @ApiModelProperty("使用次数（冗余）")
    private Long useCount;

    @ApiModelProperty("是否热门标签")
    private Integer isHot;

    private Integer status;
}
