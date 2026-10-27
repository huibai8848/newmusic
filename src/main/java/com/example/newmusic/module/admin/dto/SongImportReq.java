package com.example.newmusic.module.admin.dto;

import com.example.newmusic.module.music.dto.SongSaveReq;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.List;

@Data
@ApiModel("歌曲批量导入（JSON）")
public class SongImportReq {

    @ApiModelProperty(value = "歌曲行，单次最多 200 行；逐行独立事务", required = true)
    @NotEmpty(message = "导入内容不能为空")
    @Size(max = 200, message = "单次最多导入 200 行")
    private List<SongSaveReq> rows;
}
