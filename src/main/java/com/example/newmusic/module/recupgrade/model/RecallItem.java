package com.example.newmusic.module.recupgrade.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/** 单个召回结果。分数是源内原始分，融合时统一归一化。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecallItem {

    private Long songId;
    private Double score;
    private Map<String, Object> extra = new LinkedHashMap<>();

    public static RecallItem of(Long songId, double score) {
        return new RecallItem(songId, score, new LinkedHashMap<String, Object>());
    }
}
