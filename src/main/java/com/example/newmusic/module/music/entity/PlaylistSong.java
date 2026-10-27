package com.example.newmusic.module.music.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseLinkEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 歌单歌曲关联（物理删除，唯一索引 uk_pl_song 保证同一歌单不重复加歌）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_playlist_song")
@ApiModel("歌单歌曲关联")
public class PlaylistSong extends BaseLinkEntity {

    private static final long serialVersionUID = 1L;

    /** BR-04-012：排序号步长，稀疏插入避免全表重排 */
    public static final int SORT_STEP = 100;

    private Long playlistId;
    private Long songId;

    @ApiModelProperty("排序号")
    private Integer sortOrder;

    @ApiModelProperty("添加人")
    private Long addBy;
}
