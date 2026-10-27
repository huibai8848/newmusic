package com.example.newmusic.module.socialupgrade.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
public class PostAuditReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "请选择审核结果")
    private Boolean pass;
    private String reason;
}
