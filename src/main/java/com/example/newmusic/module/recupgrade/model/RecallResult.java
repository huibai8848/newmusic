package com.example.newmusic.module.recupgrade.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单个召回源的执行结果（成功或失败都要返回，供埋点与监控）。 */
@Data
public class RecallResult {

    private String source;
    private List<RecallItem> items = Collections.emptyList();
    private long costMs;
    private boolean success;
    private String errorMsg;

    public static RecallResult ok(String source, List<RecallItem> items, long costMs) {
        RecallResult result = new RecallResult();
        result.source = source;
        result.items = items == null ? Collections.<RecallItem>emptyList() : items;
        result.costMs = costMs;
        result.success = true;
        return result;
    }

    public static RecallResult failed(String source, long costMs, String errorMsg) {
        RecallResult result = new RecallResult();
        result.source = source;
        result.items = new ArrayList<>();
        result.costMs = costMs;
        result.success = false;
        result.errorMsg = errorMsg;
        return result;
    }

    public int size() {
        return items == null ? 0 : items.size();
    }
}
