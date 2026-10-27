package com.example.newmusic.module.recupgrade.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 批量特征结果（FR-24-005）。缺失特征不报错，统一进 {@code missingFeatures}。 */
@Data
public class FeatureVector {

    private Map<String, Double> userFeatures = new LinkedHashMap<>();
    private Map<Long, Map<String, Double>> songFeatures = new LinkedHashMap<>();
    private Map<String, Double> contextFeatures = new LinkedHashMap<>();
    private String featureVersion = "v1";
    private long costMs;
    private Set<String> missingFeatures = new LinkedHashSet<>();

    public double songFeature(Long songId, String name, double defaultValue) {
        Map<String, Double> features = songFeatures.get(songId);
        if (features == null) {
            return defaultValue;
        }
        Double value = features.get(name);
        if (value == null || value.isNaN() || value.isInfinite()) {
            return defaultValue;
        }
        return value;
    }

    public double userFeature(String name, double defaultValue) {
        Double value = userFeatures.get(name);
        return value == null || value.isNaN() || value.isInfinite() ? defaultValue : value;
    }
}
