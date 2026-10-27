package com.example.newmusic.module.dataplatform.model;

import lombok.Data;

/** 播放历史维度聚合（取 Top 风格/语种/歌手用）。 */
@Data
public class HistoryAggRow {
    private Long userId;
    private String genre;
    private String language;
    private String artistName;
    private Long cnt;
}
