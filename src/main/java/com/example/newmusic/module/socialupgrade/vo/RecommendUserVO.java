package com.example.newmusic.module.socialupgrade.vo;

import com.example.newmusic.module.social.vo.UserBriefVO;
import lombok.Data;

import java.io.Serializable;

@Data
public class RecommendUserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private UserBriefVO user;
    /** 回关（对方关注了我） */
    private Boolean followBack;
    private String reason;
    /** mutual / second_degree / region / hot */
    private String source;
    private Integer mutualCount;
}
