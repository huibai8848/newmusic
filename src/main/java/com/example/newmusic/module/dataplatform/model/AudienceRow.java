package com.example.newmusic.module.dataplatform.model;

import lombok.Data;

/** 人群圈选扫描到的标签行。 */
@Data
public class AudienceRow {
    private Long userId;
    private String tagName;
    private String tagValue;
}
