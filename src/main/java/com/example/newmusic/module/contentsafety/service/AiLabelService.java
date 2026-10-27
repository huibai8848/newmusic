package com.example.newmusic.module.contentsafety.service;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.contentsafety.dto.AuditRequest;
import com.example.newmusic.module.contentsafety.dto.AuditResult;
import com.example.newmusic.module.music.entity.Playlist;
import com.example.newmusic.module.music.mapper.PlaylistMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 生成内容标识（FR-27-010）。
 *
 * <p>标识落库而不是只在接口层拼装，原因是用户保存/编辑后接口会重新读库，
 * 只有持久化才能保证「用户不能删除标识」。普通编辑 DTO 不包含 ai 字段，
 * 因此不会有被覆盖的入口；标记动作只对后台开放。
 *
 * <p>本期实际产生 AI 文本的只有歌单名称/简介，其他内容类型返回 false，
 * 避免前端对所有卡片都显示「AI 生成」。
 */
@Slf4j
@Service
public class AiLabelService {

    private final PlaylistMapper playlistMapper;
    private final ContentAuditService contentAuditService;

    public AiLabelService(PlaylistMapper playlistMapper, ContentAuditService contentAuditService) {
        this.playlistMapper = playlistMapper;
        this.contentAuditService = contentAuditService;
    }

    /**
     * 标记歌单为 AI 生成，并立刻走一次内容审核（BR-27-113）。
     * 审核未通过时先隐藏，等人工结论；通过则保持可见。
     */
    @Transactional
    public Map<String, Object> markPlaylist(Long playlistId, String model, String promptVersion) {
        Playlist playlist = playlistMapper.selectById(playlistId);
        if (playlist == null) {
            throw new BizException(ResultCode.PLAYLIST_NOT_FOUND);
        }
        Playlist update = new Playlist();
        update.setId(playlistId);
        update.setAiGenerated(1);
        update.setAiModel(StringUtils.hasText(model) ? model : "unknown");
        update.setAiPromptVersion(StringUtils.hasText(promptVersion) ? promptVersion : "v1");
        update.setAiGeneratedAt(LocalDateTime.now());
        playlistMapper.updateById(update);

        String text = (playlist.getName() == null ? "" : playlist.getName()) + "\n"
                + (playlist.getDescription() == null ? "" : playlist.getDescription());
        AuditRequest request = AuditRequest.of(
                "PLAYLIST", playlistId, text, playlist.getUserId(), "AI_PLAYLIST");
        AuditResult result = contentAuditService.audit(request);
        contentAuditService.attachContent(result, request, playlistId);
        if (!result.isPass()) {
            Playlist hide = new Playlist();
            hide.setId(playlistId);
            hide.setStatus(0);
            hide.setAuditStatus(AuditResult.REJECT.equals(result.getDecision()) ? 2 : 0);
            playlistMapper.updateById(hide);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("playlistId", String.valueOf(playlistId));
        out.put("aiGenerated", true);
        out.put("decision", result.getDecision());
        out.put("taskId", result.getTaskId() == null ? null : String.valueOf(result.getTaskId()));
        return out;
    }

    /** C 端/后台统一读取标识。 */
    public Map<String, Object> labels(String contentType, Long contentId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contentType", contentType);
        out.put("contentId", contentId == null ? null : String.valueOf(contentId));
        out.put("aiGenerated", false);
        if ("PLAYLIST".equalsIgnoreCase(contentType) && contentId != null) {
            Playlist playlist = playlistMapper.selectById(contentId);
            if (playlist != null && Integer.valueOf(1).equals(playlist.getAiGenerated())) {
                out.put("aiGenerated", true);
                out.put("label", "AI 生成");
                out.put("aiModel", playlist.getAiModel());
                out.put("aiPromptVersion", playlist.getAiPromptVersion());
                out.put("aiGeneratedAt", playlist.getAiGeneratedAt());
            }
        }
        return out;
    }

    /**
     * 显式删除标识：任何角色都不允许（BR-27-114）。
     * 保留这个入口是为了给出明确错误码，而不是静默忽略。
     */
    public void forbidRemoval() {
        throw new BizException(ResultCode.AI_LABEL_REMOVAL_FORBIDDEN);
    }
}
