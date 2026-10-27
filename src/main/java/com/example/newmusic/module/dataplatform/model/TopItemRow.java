package com.example.newmusic.module.dataplatform.model;

import lombok.Data;

/** Top 榜单条目（歌曲/房间）。 */
@Data
public class TopItemRow {
    private Long id;
    private String name;
    private Long cnt;
}
