package com.example.newmusic.module.socialupgrade.vo;

import com.example.newmusic.module.social.vo.UserBriefVO;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class PostVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private UserBriefVO author;
    private Integer type;
    private String content;
    private List<String> images;
    private SongCard song;
    private PlaylistCard playlist;
    private String lyricSnippet;
    private List<String> topics;
    private List<MentionedUser> mentionedUsers;
    private Long repostPostId;
    private Long rootPostId;
    /** 转发时内嵌的原动态（只一层）；原动态已删时 deleted=true */
    private PostVO original;
    private Integer likeCount;
    private Integer commentCount;
    private Integer repostCount;
    private Integer collectCount;
    private Integer viewCount;
    private Integer visibility;
    private Integer status;
    private Boolean edited;
    private String ipRegion;
    private LocalDateTime createTime;
    private Boolean liked;
    private Boolean collected;
    private Boolean authorSelf;
    private Boolean deleted;

    @Data
    public static class SongCard implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String name;
        private String cover;
        private String artistName;
        private Boolean available;
    }

    @Data
    public static class PlaylistCard implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String name;
        private String cover;
        private Boolean available;
    }

    @Data
    public static class MentionedUser implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long userId;
        private String nickname;
        private Integer offset;
        private Integer length;
        private Boolean clickable;
    }
}
