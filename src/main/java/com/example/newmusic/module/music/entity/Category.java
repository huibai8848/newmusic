package com.example.newmusic.module.music.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_category")
@ApiModel("分类")
public class Category extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("分类名")
    private String name;

    @ApiModelProperty("分类编码")
    private String code;

    @ApiModelProperty("父分类ID，0 为顶级")
    private Long parentId;

    private String icon;
    private Integer sortOrder;
    private Integer status;
}
