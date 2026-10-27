package com.example.newmusic.module.ai.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * AI 输出内容审核（BR-23-010 / BR-23-009）。
 *
 * <p><b>当前默认词库为空，这是刻意的，也必须让使用者知道。</b>词库属模块 18（内容安全），
 * 本机没有 {@code t_sensitive_word} 表。沿用模块 06 敏感词词典的处理：
 * 代码路径、拦截行为、配置入口（{@code ai.sensitive.words}）全部就位，默认不拦任何词。
 * 把「有这个类」当成「内容安全已上线」是本模块最容易产生的误判。
 *
 * <p>审核不通过时**不写缓存**、不计入成功调用：否则一次误生成的违规内容会被永久缓存
 * （歌词翻译的 TTL 就是永久），人工发现后还得逐个清缓存。
 */
@Slf4j
@Component
public class AiContentAudit {

    private final AiConfigService configService;

    public AiContentAudit(AiConfigService configService) {
        this.configService = configService;
    }

    public boolean isBlocked(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        Set<String> words = configService.sensitiveWords();
        if (words.isEmpty()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (normalized.contains(word)) {
                log.warn("AI 输出命中敏感词，已拦截 word={}", word);
                return true;
            }
        }
        return false;
    }
}
