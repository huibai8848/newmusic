package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.List;

@Data
@ApiModel("批量审核")
public class AuditBatchDecisionReq {

    @NotEmpty(message = "请选择任务")
    @Size(max = 100, message = "单次最多 100 条")
    private List<Long> taskIds;

    @NotBlank(message = "请选择审核结论")
    private String decision;

    @Size(max = 500)
    private String rejectReason;

    private String contentAction;
    private String userAction;
    private Integer userActionDays;
    private Boolean notify;

    @ApiModelProperty("批量处置需二次确认")
    private Boolean confirmed;
}
