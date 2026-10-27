package com.example.newmusic.module.social.vo;

import io.swagger.annotations.ApiModel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ApiModel("举报理由")
public class ReportReasonVO {

    private String code;

    private String label;

    private String category;

    /** 1 高优先级 2 普通 */
    private Integer priority;
}
