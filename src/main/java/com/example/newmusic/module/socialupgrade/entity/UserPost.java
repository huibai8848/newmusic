package com.example.newmusic.module.socialupgrade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户动态（模块 16 FR-25-003）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_post")
public class UserPost extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final int TYPE_TEXT = 1;
    public static final int TYPE_SONG = 2;
    public static final int TYPE_PLAYLIST = 3;
    public static final int TYPE_LYRIC = 4;
    public static final int TYPE_CHECKIN = 5;
    public static final int TYPE_PLAYLIST_CREATED = 6;
    public static final int TYPE_REPOST = 7;

    public static final int STATUS_AUDITING = 0;
    public static final int STATUS_PUBLISHED = 1;
    public static final int STATUS_DELETED = 2;
    /** 疑似敏感词，仅自己可见，转人工审核 */
    public static final int STATUS_SELF_ONLY = 3;
    /** 管理员删除 */
    public static final int STATUS_ADMIN_DELETED = 4;

    public static final int VISIBILITY_PUBLIC = 0;
    public static final int VISIBILITY_FOLLOWERS = 1;
    public static final int VISIBILITY_SELF = 2;

    private Long userId;
    private Integer type;
    private String content;
    /** JSON 数组 */
    private String images;
    private Long songId;
    private Long playlistId;
    private String lyricSnippet;
    private Long repostPostId;
    private Long rootPostId;
    /** JSON 数组 */
    private String topics;
    private Integer likeCount;
    private Integer commentCount;
    private Integer repostCount;
    private Integer collectCount;
    private Integer viewCount;
    private Integer visibility;
    private Integer status;
    private Integer auditStatus;
    private String auditReason;
    private Integer imageAuditStatus;
    private Integer edited;
    private String ipRegion;
}
