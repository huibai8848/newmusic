package com.example.newmusic.module.music.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.util.List;

@Data
@ApiModel("歌曲打标签请求（全量覆盖）")
public class SongTagReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "歌曲ID", required = true)
    @NotNull(message = "歌曲ID不能为空")
    private Long songId;

    @ApiModelProperty("标签ID列表，最多 10 个；传空数组表示清空")
    @Size(max = 10, message = "单曲最多 10 个标签")
    private List<Long> tagIds;
}
