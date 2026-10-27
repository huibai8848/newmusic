package com.example.newmusic.module.music.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
@ApiModel("分类新增/编辑请求")
public class CategorySaveReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "分类名", required = true)
    @NotBlank(message = "分类名不能为空")
    @Size(max = 50, message = "分类名过长")
    private String name;

    @ApiModelProperty(value = "分类编码，全局唯一", required = true)
    @NotBlank(message = "分类编码不能为空")
    @Pattern(regexp = "^[a-z0-9_]{2,32}$", message = "编码仅支持小写字母/数字/下划线")
    private String code;

    @ApiModelProperty("父分类ID，0 为顶级")
    private Long parentId;

    private String icon;
    private Integer sortOrder;

    @ApiModelProperty("1启用 0禁用")
    private Integer status;

    @ApiModelProperty("乐观锁版本号；更新（PUT）时必传")
    private Integer version;
}
