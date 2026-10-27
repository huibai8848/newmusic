package com.example.newmusic.module.music.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
@ApiModel("标签新增/编辑请求")
public class TagSaveReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "标签名", required = true)
    @NotBlank(message = "标签名不能为空")
    @Size(max = 30, message = "标签名过长")
    private String name;

    @ApiModelProperty("1风格 2场景 3情绪 4年代 5语种 6自定义")
    private Integer tagType;

    @ApiModelProperty("是否热门 0否 1是")
    private Integer isHot;

    @ApiModelProperty("1启用 0禁用")
    private Integer status;

    @ApiModelProperty("乐观锁版本号；更新（PUT）时必传")
    private Integer version;
}
