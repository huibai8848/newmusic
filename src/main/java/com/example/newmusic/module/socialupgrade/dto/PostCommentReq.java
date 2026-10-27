package com.example.newmusic.module.socialupgrade.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

/** 动态评论：目标由路径参数决定，请求体只带内容与回复关系。 */
@Data
public class PostCommentReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "评论内容不能为空")
    private String content;
    private Long rootId;
    private Long parentId;
}
