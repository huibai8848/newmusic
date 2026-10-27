package com.example.newmusic.module.social.vo;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.util.Map;

@Data
@ApiModel("批量点赞状态")
public class LikeBatchCheckVO {

    private String targetType;

    /** targetId -> 是否已点赞 */
    private Map<Long, Boolean> liked;

    /** targetId -> 总点赞数 */
    private Map<Long, Long> counts;
}
