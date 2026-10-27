package com.example.newmusic.module.recommend.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 相似歌曲预计算表 t_song_similar（PRD 17 FR-17-004）。
 *
 * <p>在线只读；离线任务每天重算一次并覆盖。本机没有 Milvus，相似度使用
 * 标签/流派/语种/年代/BPM 的可解释加权，见 {@code SimilarPrecomputeJob}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_song_similar")
public class SongSimilar extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long songId;
    private Long similarSongId;
    private BigDecimal score;
    private Integer rankNo;
}
