package com.example.newmusic.module.dict.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("词典同步结果")
public class DictSyncResultVO {

    @ApiModelProperty("扫描的歌手/词条数")
    private int scanned;

    private int created;

    private int updated;

    private int skipped;

    private long costMs;

    private String message;
}
