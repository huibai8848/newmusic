package com.example.newmusic.module.social.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@ApiModel("新建/重命名收藏夹")
public class FolderSaveReq {

    @NotBlank(message = "请输入收藏夹名称")
    @Size(max = 30, message = "收藏夹名称不能超过 30 字")
    private String name;
}
