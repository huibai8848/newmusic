package com.example.newmusic.module.contentsafety.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AuditResult {

    public static final String PASS = "PASS";
    public static final String REJECT = "REJECT";
    public static final String REVIEW = "REVIEW";

    private String decision = PASS;
    private String reason;
    private List<String> hitWords = new ArrayList<>();
    private int riskLevel;
    private String auditSource;
    private long costMs;
    private String detail;
    private int wordVersion;
    private String modelVersion;
    private boolean incomplete;
    private Long recordId;
    private Long taskId;

    public boolean isPass() {
        return PASS.equals(decision);
    }

    public boolean isReject() {
        return REJECT.equals(decision);
    }

    public boolean isReview() {
        return REVIEW.equals(decision);
    }
}
