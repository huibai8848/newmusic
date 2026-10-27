package com.example.newmusic.module.ai.dto;

import lombok.Data;

/** 后台手动触发标注：带 songId 处理单曲，不带则触发一批。 */
@Data
public class AiAnnotateReq {

    /** EMOTION_TAG（默认）/ LYRIC_TRANS */
    private String scene;

    private Long songId;

    /** 批量时的上限，缺省用服务端配置 */
    private Integer limit;
}
