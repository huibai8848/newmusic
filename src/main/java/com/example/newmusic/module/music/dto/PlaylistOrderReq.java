package com.example.newmusic.module.music.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.util.List;

@Data
@ApiModel("歌单重排请求")
public class PlaylistOrderReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 完整的有序歌曲ID列表（前端拖拽后回传）。
     *
     * <p>未出现在列表中的既有歌曲会被追加到末尾，避免误删顺序。
     */
    @ApiModelProperty(value = "歌曲ID有序数组，按目标顺序排列", required = true)
    @NotEmpty(message = "歌曲顺序不能为空")
    @Size(max = 1000, message = "单个歌单最多 1000 首")
    private List<Long> songIds;
}
