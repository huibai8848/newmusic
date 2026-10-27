package com.example.newmusic.module.music.dto;

import com.example.newmusic.common.query.PageQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("歌曲查询参数")
public class SongQueryReq extends PageQuery {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("歌手ID")
    private Long artistId;

    @ApiModelProperty("专辑ID")
    private Long albumId;

    @ApiModelProperty("流派")
    private String genre;

    @ApiModelProperty("语种")
    private String language;

    @ApiModelProperty("状态 0下架 1上架（后台可不传）")
    private Integer status;

    @ApiModelProperty("审核状态 0待审 1通过 2拒绝")
    private Integer auditStatus;

    @ApiModelProperty("标签ID（走 t_song_tag 子查询）")
    private Long tagId;

    @ApiModelProperty("BPM 下限")
    private Integer bpmMin;

    @ApiModelProperty("BPM 上限")
    private Integer bpmMax;

    @ApiModelProperty("排除的歌曲ID（最多 200 个）")
    private List<Long> excludeIds;

    @ApiModelProperty("仅看某歌单内的歌曲")
    private Long playlistId;
}
