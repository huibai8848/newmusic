package com.example.newmusic.module.socialupgrade.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class ProfileVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String userCode;
    private String nickname;
    private String avatar;
    private String coverUrl;
    private String bio;
    private Integer gender;
    private String birthdayText;
    private String region;
    private List<String> tags;
    private Integer certType;
    private String certInfo;
    private String ipRegion;
    private LocalDateTime joinTime;
    private Integer followCount;
    private Integer fansCount;
    private Integer postCount;
    private Integer likeReceivedCount;
    private Boolean followed;
    private Boolean mutual;
    private Boolean self;
    private Boolean blocked;
    private Boolean banned;
    /** 访客是否有权查看动态/歌单等主体内容 */
    private Boolean contentVisible;
    /** 被禁止时的提示，如「关注后可查看」 */
    private String visibilityHint;
    private Map<String, Object> privacy;
}
