package com.example.newmusic.module.recupgrade.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@ApiModel("推荐流")
public class RecFeedVO {

    @ApiModelProperty("链路ID，后续埋点必须带上")
    private String traceId;

    private String scene;

    private List<RecFeedItemVO> list = new ArrayList<>();

    @ApiModelProperty("是否降级（EX-24-001-5 / EX-24-003-4）")
    private Boolean degraded;

    @ApiModelProperty("冷启动阶段 S0~S3")
    private String coldStartStage;

    @ApiModelProperty("命中的实验分组")
    private Map<String, String> experiments = new LinkedHashMap<>();

    @ApiModelProperty("排序模型版本")
    private String modelVersion;

    @ApiModelProperty("总耗时（毫秒）")
    private Long costMs;

    @ApiModelProperty("各段耗时")
    private Map<String, Long> timings = new LinkedHashMap<>();
}
