package com.example.newmusic.module.dataplatform.support;

/**
 * IP → 地区。
 *
 * <p>本机没有 IP 库（PRD 26 允许的依赖里没有 GeoIP），因此只区分内网/未知，
 * 不以省份猜测充数。接入真实 IP 库时只需替换 {@link #resolve(String)}。
 */
public final class IpRegionSupport {

    private IpRegionSupport() {
    }

    public static String resolve(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return "未知";
        }
        String value = ip.trim();
        if ("127.0.0.1".equals(value) || "::1".equals(value) || "0:0:0:0:0:0:0:1".equals(value)) {
            return "内网";
        }
        if (value.startsWith("10.") || value.startsWith("192.168.")
                || value.startsWith("172.16.") || value.startsWith("172.17.") || value.startsWith("172.18.")
                || value.startsWith("172.19.") || value.startsWith("172.2")
                || value.startsWith("172.30.") || value.startsWith("172.31.")) {
            return "内网";
        }
        return "未知";
    }
}
