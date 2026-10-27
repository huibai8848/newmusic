package com.example.newmusic.module.dataplatform.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.Map;

/**
 * 人群圈选（BR-26-040）。
 *
 * <p>条件 JSON 支持嵌套布尔组合：
 * <pre>
 * {"logic":"AND","conditions":[
 *   {"tagName":"lifecycle","op":"eq","value":"新用户"},
 *   {"logic":"OR","conditions":[{"tagName":"activity_level","op":"in","values":["高频","中频"]},
 *                                {"tagName":"use_depth","op":"eq","value":"重度"}]}
 * ]}
 * </pre>
 * 叶子节点 op：eq / neq / in / not_in / contains / gte / lte / gt / lt / exists。
 */
@Data
@ApiModel("人群圈选")
public class AudienceReq {

    @ApiModelProperty(value = "人群名", example = "高活新用户")
    private String name;

    @ApiModelProperty(value = "圈选条件 JSON", required = true)
    private Map<String, Object> conditions;
}
