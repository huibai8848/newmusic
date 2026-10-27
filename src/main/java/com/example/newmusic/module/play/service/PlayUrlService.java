package com.example.newmusic.module.play.service;

import com.example.newmusic.module.play.dto.PlayUrlVO;

/**
 * 播放地址签发（PRD FR-16-001 步骤[3]）。
 */
public interface PlayUrlService {

    /**
     * 签发播放地址。
     *
     * @param songId   歌曲 ID
     * @param quality  请求音质 {@code standard/high/lossless}，为空按 standard；无该档时逐级降级
     * @param userId   当前登录用户 ID；未登录传 null（签名用游客占位）
     * @return 带 {@code expires} 与 {@code sign} 的地址及元信息
     */
    PlayUrlVO issue(Long songId, String quality, Long userId);
}
