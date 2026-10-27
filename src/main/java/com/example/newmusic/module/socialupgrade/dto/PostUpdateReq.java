package com.example.newmusic.module.socialupgrade.dto;

import lombok.Data;

import javax.validation.constraints.Size;
import java.io.Serializable;
import java.util.List;

@Data
public class PostUpdateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @Size(max = 500, message = "动态正文最多 500 字")
    private String content;
    @Size(max = 9, message = "图片最多 9 张")
    private List<String> images;
    @Size(max = 3, message = "话题最多 3 个")
    private List<String> topics;
    @Size(max = 10, message = "最多 @ 10 个用户")
    private List<MentionRef> mentions;
    private Integer visibility;
}
