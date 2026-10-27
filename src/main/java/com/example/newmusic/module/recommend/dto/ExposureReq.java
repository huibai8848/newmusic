package com.example.newmusic.module.recommend.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
@ApiModel("推荐曝光上报")
public class ExposureReq {

    @NotEmpty(message = "songIds 不能为空")
    private List<Long> songIds;

    @ApiModelProperty("目前只处理 DAILY")
    private String scene = "DAILY";
}
