package com.example.newmusic.module.socialupgrade.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class FeedVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<PostVO> list;
    /** {@code lastScore_lastPostId}，为空表示没有下一页 */
    private String nextCursor;
    private Boolean hasMore;
    private Long newCount;
}
