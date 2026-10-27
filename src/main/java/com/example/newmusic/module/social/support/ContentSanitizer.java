package com.example.newmusic.module.social.support;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 评论/私信文本清洗（模块 10，系分 D-03）。
 *
 * <p>不把 {@code <} 转义成 {@code &lt;}：前端用 React 文本节点渲染，服务端再转义一次
 * 用户会看到字面量。这里直接**剔除 HTML 标签**，数据库存纯文本，前端按文本渲染。
 *
 * <p>外部链接按 BR-19-013 降级为纯文本——本方法不生成任何链接结构。
 */
@Component
public class ContentSanitizer {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200F\\uFEFF]");

    private static final int MAX_LENGTH = 500;

    /**
     * @throws BizException 内容为空、超长或纯表情
     */
    public String sanitizeComment(String raw) {
        String text = clean(raw);
        int length = text.codePointCount(0, text.length());
        if (length == 0) {
            throw new BizException(ResultCode.COMMENT_ILLEGAL);
        }
        if (length > MAX_LENGTH) {
            throw new BizException(ResultCode.COMMENT_ILLEGAL, "评论内容不能超过 500 字");
        }
        if (emojiOnly(text)) {
            throw new BizException(ResultCode.COMMENT_ILLEGAL, "评论不能只包含表情");
        }
        return text;
    }

    public String sanitizeMessage(String raw, int maxLength) {
        String text = clean(raw);
        int length = text.codePointCount(0, text.length());
        if (length == 0) {
            throw new BizException(ResultCode.MESSAGE_ILLEGAL, "消息内容不能为空");
        }
        if (length > maxLength) {
            throw new BizException(ResultCode.MESSAGE_ILLEGAL, "消息内容过长");
        }
        return text;
    }

    public String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = ZERO_WIDTH.matcher(raw).replaceAll("");
        text = HTML_TAG.matcher(text).replaceAll("");
        text = CONTROL.matcher(text).replaceAll("");
        return text.trim();
    }

    private static boolean emojiOnly(String text) {
        boolean sawEmoji = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                continue;
            }
            if (isEmoji(cp)) {
                sawEmoji = true;
                continue;
            }
            return false;
        }
        return sawEmoji;
    }

    private static boolean isEmoji(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF)
                || cp == 0x200D
                || cp == 0x20E3
                || (cp >= 0xFE00 && cp <= 0xFE0F);
    }
}
