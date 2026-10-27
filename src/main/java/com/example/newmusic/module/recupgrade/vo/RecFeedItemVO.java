package com.example.newmusic.module.recupgrade.vo;

import com.example.newmusic.module.music.entity.Song;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@ApiModel("推荐流条目")
public class RecFeedItemVO {

    @ApiModelProperty("在本次返回中的位置，从 1 开始（埋点回传用）")
    private Integer position;

    private Song song;

    @ApiModelProperty("推荐理由，≤20 字")
    private String reason;

    @ApiModelProperty("融合分，仅排查用")
    private Double score;

    @ApiModelProperty("召回来源及贡献分")
    private Map<String, Double> sources = new LinkedHashMap<>();

    @ApiModelProperty("是否探索位（BR-24-064）")
    private Boolean explored;

    @ApiModelProperty("是否运营置顶")
    private Boolean editor;

    @ApiModelProperty("是否新歌扶持")
    private Boolean newSong;

    @ApiModelProperty("是否长尾")
    private Boolean longTail;
}
