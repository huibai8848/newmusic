package com.example.newmusic.module.music.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.util.List;

@Data
@ApiModel("歌单加歌/移歌请求")
public class PlaylistSongReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "歌曲ID列表", required = true)
    @NotEmpty(message = "歌曲ID不能为空")
    @Size(max = 500, message = "单次最多操作 500 首")
    private List<Long> songIds;
}
