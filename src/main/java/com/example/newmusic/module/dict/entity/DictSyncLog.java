package com.example.newmusic.module.dict.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 词典同步/重载日志（模块 11）。ES 拉取、手动重载、重建索引、歌手同步都记在这里，
 * 后台「生效状态」用它回答「上次 ES 是什么时候来拉的」（BR-20-064）。
 */
@Data
@TableName("t_dict_sync_log")
@ApiModel("词典同步日志")
public class DictSyncLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @ApiModelProperty("ES_PULL / RELOAD / REINDEX / ARTIST_SYNC")
    private String syncType;

    @ApiModelProperty("SUCCESS / FAIL / SKIPPED")
    private String status;

    private Integer itemCount;

    private Long costMs;

    private String message;

    private LocalDateTime createTime;
}
