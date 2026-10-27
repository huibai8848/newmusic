package com.example.newmusic.module.socialupgrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户主页扩展（模块 16 FR-25-001）。
 *
 * <p>不继承 {@code BaseEntity}：主键是 {@code user_id} 而不是 {@code id}，
 * 且这张表没有逻辑删除语义；资料只随用户存在而存在。
 */
@Data
@TableName("t_user_profile")
public class UserProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    private String coverUrl;
    /** 对应 PRD 的 bio；为兼容模块 10 的 t_user.signature，两处会同步。 */
    private String bio;
    private Integer gender;
    private LocalDate birthday;
    private String region;
    /** JSON 数组字符串，最多 5 个标签 */
    private String tags;
    private String userCode;
    private Integer followCount;
    private Integer fansCount;
    private Integer postCount;
    private Integer likeReceivedCount;
    private Long totalPlayDuration;
    private Integer listenDays;
    /** 听歌统计逐项隐私 + 展示开关，JSON */
    private String privacyJson;
    /** 0公开 1仅关注者 2仅自己（BR-25-001，昵称/头像/关注数仍公开） */
    private Integer profileVisibility;
    private Integer certType;
    private String certInfo;
    private LocalDateTime nicknameUpdateTime;
    private LocalDate bioUpdateDate;
    private LocalDate avatarUpdateDate;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
