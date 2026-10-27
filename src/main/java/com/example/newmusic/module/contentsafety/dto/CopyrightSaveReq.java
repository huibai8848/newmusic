package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.time.LocalDate;

@Data
@ApiModel("版权信息")
public class CopyrightSaveReq {

    @NotNull(message = "请选择歌曲")
    private Long songId;

    @NotBlank(message = "版权方不能为空")
    @Size(max = 256)
    private String copyrightOwner;

    private String licenseType;
    private String licenseRegion;
    private LocalDate licenseStart;

    @NotNull(message = "授权结束时间不能为空")
    private LocalDate licenseEnd;

    private String licenseScope;
    private String contractNo;
    private String contractFile;
}
