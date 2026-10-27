package com.example.newmusic.module.dataplatform.support;

import java.util.regex.Pattern;

/**
 * 事件名校验（BR-26-002）。
 *
 * <p>规范：{@code {domain}_{object}_{action}}，全小写蛇形，2~6 段，总长不超过 64。
 * 允许两段是有意为之：PRD 的事件目录与漏斗 SQL 本身就用 {@code page_view}、
 * {@code page_leave}、{@code search_submit}、{@code room_exit} 这类
 * {@code domain_action} 事件，校验比目录严会把页面曝光整条链路打成脏数据。
 * 只校验「形状」不校验域名白名单——新增域（如 data）不需要改代码。
 */
public final class EventNameValidator {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9]*(?:_[a-z0-9]+){1,5}$");
    public static final int MAX_LENGTH = 64;

    private EventNameValidator() {
    }

    /** @return null 表示合法；否则返回可直接展示的原因 */
    public static String validate(String eventName) {
        if (eventName == null || eventName.trim().isEmpty()) {
            return "事件名为空";
        }
        String name = eventName.trim();
        if (name.length() > MAX_LENGTH) {
            return "事件名超过 " + MAX_LENGTH + " 字符";
        }
        if (!NAME.matcher(name).matches()) {
            return "事件名必须符合 {domain}_{object}_{action} 的全小写蛇形规范";
        }
        return null;
    }

    public static boolean isValid(String eventName) {
        return validate(eventName) == null;
    }
}
