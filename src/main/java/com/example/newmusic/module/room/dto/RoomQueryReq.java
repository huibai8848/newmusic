package com.example.newmusic.module.room.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("房间列表查询")
public class RoomQueryReq extends PageQuery {

    private static final long serialVersionUID = 1L;

    /** recommend 推荐 / hot 热门 / new 最新 / follow 关注 / mine 我的 */
    @ApiModelProperty(value = "列表维度", example = "recommend")
    private String tab = "recommend";

    @ApiModelProperty("房间类型 1公开 2私密 3密码")
    private Integer roomType;
}
