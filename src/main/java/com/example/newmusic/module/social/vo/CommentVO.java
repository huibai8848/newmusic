package com.example.newmusic.module.social.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@ApiModel("评论视图")
public class CommentVO {

    private Long id;

    private String targetType;

    private Long targetId;

    private Long userId;

    private String nickname;

    private String avatar;

    private Long rootId;

    private Long parentId;

    private Long replyUserId;

    private String replyNickname;

    private String content;

    private Integer likeCount;

    private Integer replyCount;

    @ApiModelProperty("当前登录用户是否点过赞")
    private Boolean liked;

    @ApiModelProperty("0待审 1已发布")
    private Integer status;

    @ApiModelProperty("是否本人发表")
    private Boolean mine;

    private LocalDateTime createTime;

    @ApiModelProperty("前 2 条回复；展开时返回更多")
    private List<CommentVO> replies;
}
