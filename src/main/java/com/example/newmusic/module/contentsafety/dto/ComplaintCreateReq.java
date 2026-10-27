package com.example.newmusic.module.contentsafety.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.List;

@Data
@ApiModel("登记侵权投诉")
public class ComplaintCreateReq {

    @NotBlank(message = "投诉人不能为空")
    @Size(max = 128)
    private String complainantName;

    private String complainantType;

    @Size(max = 256)
    private String contact;

    @NotEmpty(message = "请填写侵权歌曲")
    private List<Long> songIds;

    @Size(max = 1000)
    private String description;

    @Size(max = 20)
    private List<String> proofFiles;

    private Boolean statement;
}
