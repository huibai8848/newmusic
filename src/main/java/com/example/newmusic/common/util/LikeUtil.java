package com.example.newmusic.common.util;

import org.springframework.util.StringUtils;

/**
 * 模糊查询安全处理（BR-10-006）。
 *
 * <p>{@code keyword} 中的 {@code %}、{@code _}、{@code \} 必须转义，
 * 否则用户输入 {@code %} 会退化为全表匹配。
 */
public final class LikeUtil {

    private LikeUtil() {
    }

    /** 转义 LIKE 通配符，配合 MyBatis-Plus 的 {@code like()} 使用（其会自动加 %kw%）。 */
    public static String escape(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return keyword;
        }
        StringBuilder sb = new StringBuilder(keyword.length());
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
