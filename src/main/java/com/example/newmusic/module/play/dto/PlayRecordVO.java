package com.example.newmusic.module.play.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("播放上报结果")
public class PlayRecordVO {

    @ApiModelProperty("播放会话 ID（后续 /play/end 使用）")
    private Long playId;

    private Long songId;
    private String songName;
    private String artistName;

    @ApiModelProperty("是否命中 5 秒去重，命中时不会重复写历史")
    private Boolean duplicated;
}
