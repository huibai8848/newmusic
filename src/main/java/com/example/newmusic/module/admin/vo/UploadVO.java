package com.example.newmusic.module.admin.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
@ApiModel("上传结果")
public class UploadVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("可直接写入歌曲的 URL（/media/...）")
    private String url;

    @ApiModelProperty("字节数")
    private long size;

    @ApiModelProperty("原始文件名（仅用于回显）")
    private String originalName;
}
