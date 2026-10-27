package com.example.newmusic.module.search.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 热搜榜响应（§5.4）。
 *
 * <p>这个类同时是**展示快照的缓存载体**（{@code ml:search:hot:snapshot} 里存的就是它的 JSON）。
 * 让缓存与接口共用同一个类，而不是「缓存存裸数据、响应再包一层」：热搜的对外形态与
 * 内部形态本来就是一回事（不像联想，那里的高亮必须每次现算，见 §4.6）。
 */
@Data
@ApiModel("热搜榜")
public class HotSearchVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("热搜词，最多 10 条")
    private List<HotWordVO> words = new ArrayList<>();

    @ApiModelProperty("这份榜单的生成时间。同一份快照 5 分钟内所有人看到的值相同（BR-15-032）")
    private LocalDateTime refreshedAt;
}
