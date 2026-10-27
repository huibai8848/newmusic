package com.example.newmusic.module.dataplatform.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 埋点脱敏（BR-26-005 / BR-26-088）。
 *
 * <p>两类处理：
 * <ul>
 *   <li>密码、token、cookie、证件号等：<b>整键删除</b>，连密文都不留；</li>
 *   <li>手机号、邮箱：<b>掩码保留</b>（138****8000 / a***@x.com），运营仍能按渠道聚合。</li>
 * </ul>
 *
 * <p>即使键名不敏感，只要<b>整个值</b>是手机号/邮箱格式，也会被掩码——搜索词里输入手机号
 * 是很常见的泄露路径。
 */
public final class TrackSanitizer {

    private static final int MAX_DEPTH = 3;

    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /** 键名包含这些词 → 整键删除 */
    private static final String[] DROP_KEYS = {
            "password", "passwd", "pwd", "token", "secret", "authorization", "cookie",
            "idcard", "id_card", "identity", "bankcard", "bank_card", "cvv", "ssn"
    };

    /** 键名包含这些词 → 掩码值 */
    private static final String[] MASK_KEYS = {"phone", "mobile", "tel", "email", "mail"};

    private TrackSanitizer() {
    }

    /** 返回脱敏后的新 Map，不修改入参。 */
    public static Map<String, Object> sanitize(Map<String, Object> props) {
        if (props == null || props.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return sanitizeMap(props, 0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeMap(Map<String, Object> src, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : src.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String lower = key.toLowerCase(Locale.ROOT);
            Object value = entry.getValue();
            if (containsAny(lower, DROP_KEYS)) {
                continue;
            }
            if (containsAny(lower, MASK_KEYS)) {
                out.put(key, maskValue(value));
                continue;
            }
            if (depth < MAX_DEPTH && value instanceof Map) {
                out.put(key, sanitizeMap((Map<String, Object>) value, depth + 1));
            } else if (depth < MAX_DEPTH && value instanceof List) {
                out.put(key, sanitizeList((List<Object>) value, depth + 1));
            } else if (value instanceof String) {
                out.put(key, maskIfSensitiveText((String) value));
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> sanitizeList(List<Object> src, int depth) {
        List<Object> out = new ArrayList<>(src.size());
        for (Object value : src) {
            if (depth < MAX_DEPTH && value instanceof Map) {
                out.add(sanitizeMap((Map<String, Object>) value, depth + 1));
            } else if (depth < MAX_DEPTH && value instanceof List) {
                out.add(sanitizeList((List<Object>) value, depth + 1));
            } else if (value instanceof String) {
                out.add(maskIfSensitiveText((String) value));
            } else {
                out.add(value);
            }
        }
        return out;
    }

    private static Object maskValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (PHONE.matcher(text).matches()) {
            return maskPhone(text);
        }
        if (EMAIL.matcher(text).matches()) {
            return maskEmail(text);
        }
        return "***";
    }

    /** 只有「整个值就是一个号码/邮箱」时才掩码，避免把普通文本误伤。 */
    public static String maskIfSensitiveText(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (PHONE.matcher(text).matches()) {
            return maskPhone(text);
        }
        if (EMAIL.matcher(text).matches()) {
            return maskEmail(text);
        }
        return value;
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String name = email.substring(0, at);
        String domain = email.substring(at);
        return (name.length() <= 1 ? name : name.substring(0, 1)) + "***" + domain;
    }

    private static boolean containsAny(String key, String[] needles) {
        for (String needle : needles) {
            if (key.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
