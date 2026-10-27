package com.example.newmusic.module.dict.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
@ApiModel("词典批量导入请求")
public class DictImportReq {

    /**
     * 主词典格式：{@code 词条[ 词频[ 词性]]}；停用词/拼音格式由接口路径决定。
     * 用内容而不是 multipart 文件：验收脚本与 Swagger 都更容易构造，
     * 后台界面读文件后转成文本即可。
     */
    @NotBlank
    @ApiModelProperty(value = "逐行文本；# 开头为注释", required = true)
    private String content;

    @ApiModelProperty(value = "MAIN / NET_SLANG / REGION", example = "MAIN")
    private String dictType = "MAIN";

    @ApiModelProperty("true = 已存在时更新词频/词性；false = 跳过")
    private boolean overwrite = false;
}
