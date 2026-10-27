package com.example.newmusic.module.dataplatform.model;

import lombok.Data;

/** id → 名称（Top 歌曲/房间展示用）。 */
@Data
public class IdNameRow {
    private Long id;
    private String name;
}
