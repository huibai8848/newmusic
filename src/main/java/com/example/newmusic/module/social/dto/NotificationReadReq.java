package com.example.newmusic.module.social.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
@ApiModel("批量标记通知已读")
public class NotificationReadReq {

    @NotEmpty(message = "请选择通知")
    private List<Long> ids;
}
