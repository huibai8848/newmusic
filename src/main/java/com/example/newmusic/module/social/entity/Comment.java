package com.example.newmusic.module.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评论（模块 10）。两级结构：顶级评论 rootId=0，回复 rootId=根评论ID。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_comment")
@ApiModel("评论")
public class Comment extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PUBLISHED = 1;
    public static final int STATUS_DELETED = 2;
    public static final int STATUS_BLOCKED = 3;

    @ApiModelProperty("目标类型 SONG/PLAYLIST/ALBUM/ROOM/COMMENT")
    private String targetType;

    @ApiModelProperty("目标ID")
    private Long targetId;

    @ApiModelProperty("评论人")
    private Long userId;

    @ApiModelProperty("根评论ID，顶级为0")
    private Long rootId;

    @ApiModelProperty("直接父评论ID，顶级为0")
    private Long parentId;

    @ApiModelProperty("被回复用户ID")
    private Long replyUserId;

    @ApiModelProperty("纯文本内容")
    private String content;

    private Integer likeCount;

    private Integer replyCount;

    @ApiModelProperty("0待审 1已发布 2已删除 3已屏蔽")
    private Integer status;

    private String auditReason;

    private String ip;

    private String userAgent;
}
