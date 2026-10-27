package com.example.newmusic.module.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 歌词翻译响应。
 *
 * <p>{@code lines} 已经按时间轴与原文对齐，前端不需要再自己按行号拼——
 * 行号对齐在模型多输出一行/少输出一行时会整体错位，是这类功能最常见的线上问题。
 * 未生成翻译时 {@code generated=false}、每行的 {@code translation} 为 null，
 * 前端据此显示「生成翻译」按钮，而不是把 404 当成错误。
 */
@Data
@Builder
public class LyricTranslationVO {

    private Long songId;
    private String songName;
    private String language;
    private String transLang;

    /** 是否已有译文（数据库或 Redis 命中，或本次刚生成） */
    private boolean generated;

    /** 本次是否命中 Redis 缓存（命中则没有产生 AI 成本） */
    private boolean fromCache;

    private String model;
    private int costCent;

    /** 带时间轴的译文 LRC；未生成时为 null */
    private String translation;

    private List<Line> lines;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private long timeMs;
        private String original;
        private String translation;
    }
}
