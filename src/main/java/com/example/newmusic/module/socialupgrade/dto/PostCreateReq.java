package com.example.newmusic.module.socialupgrade.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.util.List;

@Data
public class PostCreateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 1纯文本 2歌曲 3歌单 4歌词 5打卡 6创建歌单 7转发（转发走专用接口） */
    @Min(value = 1, message = "动态类型不合法")
    @Max(value = 7, message = "动态类型不合法")
    private Integer type;

    @Size(max = 500, message = "动态正文最多 500 字")
    private String content;

    @Size(max = 9, message = "图片最多 9 张")
    private List<String> images;

    private Long songId;
    private Long playlistId;
    @Size(max = 100, message = "歌词片段最多 100 字")
    private String lyricSnippet;

    @Size(max = 3, message = "话题最多 3 个")
    private List<String> topics;

    @Size(max = 10, message = "最多 @ 10 个用户")
    private List<MentionRef> mentions;

    /** 0公开 1仅关注者 2仅自己 */
    private Integer visibility;
}
