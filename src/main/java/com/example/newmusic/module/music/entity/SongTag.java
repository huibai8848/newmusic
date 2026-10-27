package com.example.newmusic.module.music.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseLinkEntity;
import io.swagger.annotations.ApiModel;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 歌曲标签关联（物理删除，唯一索引 uk_song_tag 保证幂等）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_song_tag")
@ApiModel("歌曲标签关联")
public class SongTag extends BaseLinkEntity {

    private static final long serialVersionUID = 1L;

    private Long songId;
    private Long tagId;

    /** AI 标注权重，默认 1.0000 */
    private BigDecimal weight;
}
