package com.example.newmusic.module.admin.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
@ApiModel("批量上下架歌曲")
public class SongBatchStatusReq {

    @ApiModelProperty(value = "歌曲 ID 列表，单次最多 200", required = true)
    @NotEmpty(message = "请选择歌曲")
    private List<Long> ids;

    @ApiModelProperty(value = "0 下架 / 1 上架", required = true)
    @NotNull(message = "请选择目标状态")
    @Min(0)
    @Max(1)
    private Integer status;
}
