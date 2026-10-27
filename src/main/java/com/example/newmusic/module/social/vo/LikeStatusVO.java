package com.example.newmusic.module.social.vo;

import io.swagger.annotations.ApiModel;
import lombok.Data;

@Data
@ApiModel("点赞状态")
public class LikeStatusVO {

    private String targetType;

    private Long targetId;

    private Boolean liked;

    private Long count;
}
