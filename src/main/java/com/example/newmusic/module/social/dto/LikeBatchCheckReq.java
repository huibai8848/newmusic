package com.example.newmusic.module.social.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
@ApiModel("批量查询点赞状态")
public class LikeBatchCheckReq {

    @NotBlank(message = "请选择目标类型")
    private String targetType;

    @NotEmpty(message = "目标ID列表不能为空")
    private List<Long> targetIds;
}
