package com.example.newmusic.module.social.vo;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@ApiModel("分享短链")
public class ShareVO {

    private String code;

    private String shortUrl;

    private String targetUrl;

    private LocalDateTime expireTime;
}
