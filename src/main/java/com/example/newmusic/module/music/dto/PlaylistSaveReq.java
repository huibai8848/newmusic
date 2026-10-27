package com.example.newmusic.module.music.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
@ApiModel("歌单新增/编辑请求")
public class PlaylistSaveReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "歌单名", required = true)
    @NotBlank(message = "歌单名不能为空")
    @Size(max = 100, message = "歌单名不能超过 100 字")
    private String name;

    private String cover;

    @ApiModelProperty("简介")
    @Size(max = 500, message = "简介不能超过 500 字")
    private String description;

    @ApiModelProperty("0私密 1公开")
    private Integer isPublic;

    @ApiModelProperty("标签，逗号分隔，最多 5 个")
    private String tags;

    /** 仅后台可传，前台创建固定为 TYPE_USER。 */
    @ApiModelProperty("1用户 2编辑 3算法 4场景（后台）")
    private Integer playlistType;

    @ApiModelProperty("乐观锁版本号；更新（PUT）时必传")
    private Integer version;
}
