package com.example.newmusic.module.social.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.List;

@Data
@ApiModel("提交举报")
public class ReportCreateReq {

    @ApiModelProperty("SONG/COMMENT/USER/ROOM/MESSAGE/PLAYLIST/LYRIC")
    @NotBlank(message = "请选择举报目标")
    private String targetType;

    @NotBlank(message = "举报目标不能为空")
    private String targetId;

    @NotBlank(message = "请选择举报理由")
    private String reasonType;

    @Size(max = 500, message = "补充说明不能超过 500 字")
    private String reason;

    @ApiModelProperty("凭证图片 URL 列表，最多 6 张")
    private List<String> evidenceUrls;
}
