package com.example.newmusic.module.recommend.vo;

import com.example.newmusic.module.music.entity.Song;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("推荐条目")
public class RecommendItemVO {

    private Song song;

    @ApiModelProperty("推荐理由，≤20 字（BR-17-013）")
    private String reason;

    @ApiModelProperty("召回来源：preference/recent/cooccur/hot/explore/similar")
    private String recall;

    @ApiModelProperty("排序分，仅用于排查")
    private Double score;

    @ApiModelProperty("在列表中的位置，从 1 开始")
    private Integer position;

    @ApiModelProperty("是否听过；用于猜你喜欢的占比控制与排查")
    private Boolean listened;
}
