package com.example.newmusic.module.search.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 联想建议项（§5.3）。
 *
 * <p>{@code text}/{\@code type}/{\@code id}/{\@code extra} 是**可缓存的裸数据**，
 * {@code highlight} 是每次现算的展示字段——BR-15-020 让「周杰伦的歌」与「周杰伦的曲」
 * 共用一份缓存（都取前 8 个字符），而两者的高亮区间不同，所以高亮不能进缓存（§4.6）。
 * 这个类同时承担缓存序列化的载体，故 {@code highlight} 用 {@code transient} 之外的手段
 * 隔离：缓存里存的是本类的实例但 {@code highlight} 恒为 null（见 SuggestService）。
 */
@Data
@ApiModel("搜索联想项")
public class SuggestItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("建议文本")
    private String text;

    @ApiModelProperty("类型：artist / song / album / playlist")
    private String type;

    @ApiModelProperty("对应实体ID，点击后可直接跳详情")
    private Long id;

    @ApiModelProperty("补充信息：歌曲/专辑给歌手名，歌单给「N首」；歌手为空")
    private String extra;

    /**
     * 高亮后的文本。**只在 {@code text} 真的以 keyword 开头时才有值**——
     * 搜「周」时「周杰伦」有高亮，而热搜兜底带进来的「晴天」没有（§5.3）。
     */
    @ApiModelProperty("高亮后的文本，前端用 dangerouslySetInnerHTML 渲染；无高亮时为 null")
    private String highlight;
}
