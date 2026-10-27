package com.example.newmusic.module.ws.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通知（PRD 04 §6 {@code t_notification}）。
 *
 * <p><b>不继承 {@code BaseEntity}</b>：这张表的 DDL 没有 {@code deleted/version/update_time}，
 * 继承后 MyBatis-Plus 会把这些列拼进 SQL，插入直接失败。通知是追加型数据，
 * 没有「逻辑删除」和「乐观锁」的语义——过期靠定时清理，不靠标记。
 *
 * <p>同样地，{@code create_time} 没有 ON UPDATE，这里不做 BaseEntity 的自动填充。
 */
@Data
@TableName("t_notification")
@ApiModel("通知")
public class Notification implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int TYPE_SYSTEM = 1;
    public static final int TYPE_COMMENT = 2;
    public static final int TYPE_LIKE = 3;
    public static final int TYPE_FOLLOW = 4;
    public static final int TYPE_MESSAGE = 5;
    public static final int TYPE_MENTION = 6;
    public static final int TYPE_ROOM_INVITE = 7;
    public static final int TYPE_AUDIT = 8;
    public static final int TYPE_REPORT = 9;
    /** 模块 10 追加：关注歌手发新歌 */
    public static final int TYPE_NEW_SONG = 10;
    /** 模块 10 追加：账号处置（封禁/禁言） */
    public static final int TYPE_BAN = 11;
    /** 模块 10 追加：申诉结果 */
    public static final int TYPE_APPEAL = 12;
    /** 模块 10 追加：推荐提醒 */
    public static final int TYPE_RECOMMEND = 13;
    /** 模块 10 追加：关注的房主开播 */
    public static final int TYPE_ROOM_FOLLOW = 14;
    /** 模块 16 追加：动态被转发 */
    public static final int TYPE_REPOST = 15;
    /** 模块 16 追加：动态被收藏 */
    public static final int TYPE_POST_COLLECT = 16;

    // 跳转目标类型（t_notification.target_type 是 TINYINT，模块 10 只做映射，不改列）
    public static final int TARGET_SONG = 1;
    public static final int TARGET_PLAYLIST = 2;
    public static final int TARGET_ALBUM = 3;
    public static final int TARGET_COMMENT = 4;
    public static final int TARGET_USER = 5;
    public static final int TARGET_ROOM = 6;
    public static final int TARGET_ROOM_MESSAGE = 7;
    public static final int TARGET_MESSAGE = 8;
    /** 模块 16 追加：跳转用户动态 */
    public static final int TARGET_POST = 9;
    /** 模块 16 追加：跳转话题 */
    public static final int TARGET_TOPIC = 10;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @ApiModelProperty("接收者")
    private Long userId;

    @ApiModelProperty("1系统 2评论回复 3点赞 4关注 5私信 6@提醒 7房间邀请 8审核结果 9举报结果")
    private Integer notifyType;

    private String title;
    private String content;

    @ApiModelProperty("触发者")
    private Long fromUserId;
    private String fromNickname;
    private String fromAvatar;

    @ApiModelProperty("跳转目标类型")
    private Integer targetType;
    @ApiModelProperty("跳转目标ID")
    private Long targetId;

    @ApiModelProperty("0未读 1已读")
    private Integer isRead;

    private LocalDateTime readTime;

    private LocalDateTime createTime;

    /** 非数据库列：供 C 端直接展示「歌曲/歌单/评论」等语义。 */
    @TableField(exist = false)
    private String targetTypeName;
}
