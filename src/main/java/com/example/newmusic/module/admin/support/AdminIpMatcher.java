package com.example.newmusic.module.admin.support;

import org.springframework.util.StringUtils;

/**
 * 后台登录 IP 白名单匹配：支持精确 IP、前缀通配（{@code 10.0.0.*}）与 IPv4 CIDR。
 * 空名单 = 不限制。
 */
public final class AdminIpMatcher {

    private AdminIpMatcher() {
    }

    public static boolean allows(String allowList, String ip) {
        if (!StringUtils.hasText(allowList)) {
            return true;
        }
        if (!StringUtils.hasText(ip)) {
            return false;
        }
        for (String raw : allowList.split("[,，\\s]+")) {
            String rule = raw.trim();
            if (rule.isEmpty()) {
                continue;
            }
            if ("*".equals(rule) || rule.equals(ip)) {
                return true;
            }
            if (rule.endsWith("*") && ip.startsWith(rule.substring(0, rule.length() - 1))) {
                return true;
            }
            if (rule.contains("/") && cidrMatch(rule, ip)) {
                return true;
            }
        }
        return false;
    }

    private static boolean cidrMatch(String rule, String ip) {
        try {
            String[] parts = rule.split("/");
            if (parts.length != 2) {
                return false;
            }
            long network = ipv4(parts[0]);
            long address = ipv4(ip);
            if (network < 0 || address < 0) {
                return false;
            }
            int bits = Integer.parseInt(parts[1]);
            if (bits < 0 || bits > 32) {
                return false;
            }
            long mask = bits == 0 ? 0L : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
            return (network & mask) == (address & mask);
        } catch (Exception e) {
            return false;
        }
    }

    private static long ipv4(String value) {
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return -1;
        }
        long result = 0;
        for (String part : parts) {
            int n = Integer.parseInt(part);
            if (n < 0 || n > 255) {
                return -1;
            }
            result = (result << 8) | n;
        }
        return result;
    }
}
