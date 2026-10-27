package com.example.newmusic.module.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseLinkEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 歌手关注（模块 10）。与用户关注分表：歌手不是 t_user 记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_artist_follow")
public class ArtistFollow extends BaseLinkEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;

    private Long artistId;
}
