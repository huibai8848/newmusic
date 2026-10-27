package com.example.newmusic.module.contentsafety.service;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.contentsafety.mapper.ContentDispositionMapper;
import com.example.newmusic.module.contentsafety.support.SensitiveWordEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 内容处置（HIDE/DELETE/SELF_ONLY/MASK/PASS）。
 *
 * <p>「删除」也走隐藏：审核处置需要保留原文用于申诉复核与复议，物理删除会让
 * 申诉方与被处置方都失去证据。用户自己删除仍走各业务模块的原逻辑。
 *
 * <p>本类不吞异常：业务表更新失败会让外层审核事务整体回滚，避免
 * 「审核记录写成功、内容还在线」这种最难排查的状态。
 */
@Slf4j
@Service
public class ContentDispositionService {

    private final ContentDispositionMapper mapper;
    private final SensitiveWordEngine engine;

    public ContentDispositionService(ContentDispositionMapper mapper, SensitiveWordEngine engine) {
        this.mapper = mapper;
        this.engine = engine;
    }

    public boolean apply(String contentType, Long contentId, String action, Long operatorId) {
        if (!StringUtils.hasText(action) || "NONE".equalsIgnoreCase(action)) {
            return false;
        }
        if (contentId == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "处置内容 ID 不能为空");
        }
        String type = normalizeType(contentType);
        String act = action.trim().toUpperCase(Locale.ROOT);
        ensureExists(type, contentId);

        switch (act) {
            case "HIDE":
            case "DELETE":
                hide(type, contentId, operatorId);
                return true;
            case "PASS":
            case "RESTORE":
                restore(type, contentId, operatorId);
                return true;
            case "SELF_ONLY":
                return selfOnly(type, contentId, operatorId);
            case "MASK":
                return mask(type, contentId, operatorId);
            default:
                throw new BizException(ResultCode.PARAM_INVALID, "不支持的内容处置动作：" + action);
        }
    }

    @Transactional
    public void hide(String contentType, Long contentId, Long operatorId) {
        String type = normalizeType(contentType);
        switch (type) {
            case "COMMENT":
                mapper.hideComment(contentId, operatorId);
                break;
            case "POST":
                mapper.hidePost(contentId, operatorId);
                break;
            case "PLAYLIST":
                mapper.hidePlaylist(contentId, operatorId);
                break;
            case "ROOM":
                mapper.closeRoom(contentId, operatorId);
                break;
            case "MESSAGE":
                mapper.revokeMessage(contentId, operatorId);
                break;
            case "DANMAKU":
            case "ROOM_MESSAGE":
                mapper.hideRoomMessage(contentId);
                break;
            case "SONG":
                mapper.offlineSong(contentId, operatorId);
                break;
            default:
                throw new BizException(ResultCode.PARAM_INVALID, "不支持隐藏的内容类型：" + contentType);
        }
    }

    @Transactional
    public void restore(String contentType, Long contentId, Long operatorId) {
        String type = normalizeType(contentType);
        switch (type) {
            case "COMMENT":
                mapper.restoreComment(contentId, operatorId);
                break;
            case "POST":
                mapper.restorePost(contentId, operatorId);
                // 机审 REVIEW 的动态是 status=3（仅自己可见），恢复要走另一条条件更新。
                mapper.publishReviewedPost(contentId, operatorId);
                break;
            case "PLAYLIST":
                mapper.restorePlaylist(contentId, operatorId);
                break;
            case "ROOM":
                mapper.reopenRoom(contentId, operatorId);
                break;
            case "MESSAGE":
                mapper.restoreMessage(contentId, operatorId);
                break;
            case "DANMAKU":
            case "ROOM_MESSAGE":
                mapper.restoreRoomMessage(contentId);
                break;
            case "SONG":
                mapper.onlineSong(contentId, operatorId);
                break;
            default:
                throw new BizException(ResultCode.PARAM_INVALID, "不支持恢复的内容类型：" + contentType);
        }
    }

    private boolean selfOnly(String type, Long contentId, Long operatorId) {
        if (!"POST".equals(type)) {
            // 评论/弹幕没有「仅自己可见」语义，退化为隐藏，绝不静默放行。
            hide(type, contentId, operatorId);
            return true;
        }
        mapper.selfOnlyPost(contentId, operatorId);
        return true;
    }

    private boolean mask(String type, Long contentId, Long operatorId) {
        // 局部替换只对纯文本内容成立；其它类型没有安全的「部分可见」语义，退化为隐藏。
        if ("COMMENT".equals(type)) {
            String content = mapper.selectCommentContent(contentId);
            return mapper.rewriteComment(contentId, maskText(content), operatorId) > 0;
        }
        if ("DANMAKU".equals(type) || "ROOM_MESSAGE".equals(type)) {
            String content = mapper.selectRoomMessageContent(contentId);
            return mapper.rewriteRoomMessage(contentId, maskText(content)) > 0;
        }
        hide(type, contentId, operatorId);
        return true;
    }

    private void ensureExists(String type, Long contentId) {
        long count;
        switch (type) {
            case "COMMENT":
                count = mapper.countComment(contentId);
                break;
            case "POST":
                count = mapper.countPost(contentId);
                break;
            case "PLAYLIST":
                count = mapper.countPlaylist(contentId);
                break;
            case "ROOM":
                count = mapper.countRoom(contentId);
                break;
            case "MESSAGE":
                count = mapper.countMessage(contentId);
                break;
            case "DANMAKU":
            case "ROOM_MESSAGE":
                count = mapper.countRoomMessage(contentId);
                break;
            case "SONG":
                // 歌曲走 AdminContentService 的原生校验；这里不再重复查表。
                return;
            default:
                throw new BizException(ResultCode.PARAM_INVALID, "不支持的内容类型：" + type);
        }
        if (count == 0) {
            throw new BizException(ResultCode.PARAM_INVALID, "内容不存在或已被删除");
        }
    }

    public static String normalizeType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            throw new BizException(ResultCode.PARAM_INVALID, "内容类型不能为空");
        }
        String type = contentType.trim().toUpperCase(Locale.ROOT);
        if ("PRIVATE_MESSAGE".equals(type)) {
            return "MESSAGE";
        }
        if ("ROOM_CHAT".equals(type)) {
            return "ROOM_MESSAGE";
        }
        return type;
    }

    /** 仅用于测试与调试：把文本里的命中词替换成掩码，保留原文长度之外的语义。 */
    public String maskText(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        SensitiveWordEngine.Result result = engine.detect(text);
        List<SensitiveWordEngine.Match> matches = new ArrayList<>(result.getMatches());
        matches.sort((a, b) -> Integer.compare(b.getStart(), a.getStart()));
        StringBuilder sb = new StringBuilder(text);
        for (SensitiveWordEngine.Match match : matches) {
            if (match.getStart() >= 0 && match.getEnd() <= sb.length()
                    && match.getStart() < match.getEnd()) {
                sb.replace(match.getStart(), match.getEnd(), match.getWord());
            }
        }
        return sb.toString();
    }
}
