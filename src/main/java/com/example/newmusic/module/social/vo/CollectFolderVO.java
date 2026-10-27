package com.example.newmusic.module.social.vo;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@ApiModel("收藏夹")
public class CollectFolderVO {

    private Long id;

    private String name;

    private Boolean isDefault;

    private Integer itemCount;

    private Integer sortOrder;

    private LocalDateTime createTime;
}
