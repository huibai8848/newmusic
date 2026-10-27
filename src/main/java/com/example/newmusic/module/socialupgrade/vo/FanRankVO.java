package com.example.newmusic.module.socialupgrade.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

@Data
@ApiModel("粉丝榜")
public class FanRankVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;

    @ApiModelProperty("1 铁粉榜 2 互动榜 3 礼物榜")
    private Integer type;

    @ApiModelProperty("榜主是否开启了榜单展示")
    private Boolean visible;

    private LocalDate statDate;

    private List<FanRankItemVO> list;

    @Data
    @ApiModel("粉丝榜条目")
    public static class FanRankItemVO implements Serializable {

        private static final long serialVersionUID = 1L;

        private Integer rank;
        private Long userId;
        private String nickname;
        private String avatar;
        private Integer score;

        @ApiModelProperty("Top 3 铁粉标识")
        private Boolean badge;
    }
}
