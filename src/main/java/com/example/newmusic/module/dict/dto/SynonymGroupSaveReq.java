package com.example.newmusic.module.dict.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
@ApiModel("同义词组保存请求")
public class SynonymGroupSaveReq {

    @ApiModelProperty("组号；新建时为空，修改时必填")
    private Integer groupNo;

    @NotEmpty
    @ApiModelProperty(value = "组内词条，最多 20 个", required = true)
    private List<String> words;

    @ApiModelProperty(value = "主词；必须是 words 中的一项。为空时取第一个", example = "周杰伦")
    private String primaryWord;

    @ApiModelProperty(value = "EQUIVALENT / ONE_WAY", example = "EQUIVALENT")
    private String synonymType = "EQUIVALENT";

    @ApiModelProperty(value = "ARTIST / GENRE / LANGUAGE / ERA / SCENE / EMOTION / OTHER", example = "ARTIST")
    private String category = "OTHER";

    @ApiModelProperty("1 启用 0 停用")
    private Integer enabled = 1;

    @ApiModelProperty(value = "true = 等价组内所有词互搜；false 时按 ONE_WAY 处理", hidden = true)
    private Boolean equivalent;
}
