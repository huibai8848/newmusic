package com.example.newmusic.module.social.vo;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@ApiModel("黑名单项")
public class BlacklistVO {

    private Long userId;

    private String nickname;

    private String avatar;

    private LocalDateTime createTime;
}
