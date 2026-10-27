package com.example.newmusic.module.play.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 播放地址签名器（PRD BR-16-010）。
 *
 * <p>签名算法与 PRD 逐字对齐：
 * <pre>sign = HMAC_SHA256(secret, path + "|" + expires + "|" + userId)</pre>
 * 其中 {@code expires} 为<b>秒级</b>时间戳。{@code userId} 参与签名，使地址不可跨账号复用；
 * 游客用 {@link #GUEST_ID} 占位，保证 payload 的段数恒定（避免"空串"与"少一段"两种拼接歧义）。
 *
 * <p><b>本模块只负责生成与校验</b>：演示环境的静态目录不挂校验过滤器（系分 D-03），
 * 真正的防盗链在 CDN / Nginx 层拦截（模块 19）。
 */
@Component
public class PlayUrlSigner {

    /** 游客占位 ID */
    public static final long GUEST_ID = 0L;

    private static final String ALGORITHM = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final byte[] secret;
    private final long ttlSeconds;

    public PlayUrlSigner(@Value("${newmusic.play.sign-secret:newmusic-demo-secret-change-me}") String secret,
                         @Value("${newmusic.play.url-ttl-seconds:7200}") long ttlSeconds) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    /** 地址有效期（秒）。 */
    public long ttlSeconds() {
        return ttlSeconds;
    }

    /** 生成签名（小写十六进制）。 */
    public String sign(String path, long expires, Long userId) {
        return encodeHex(hmac(path, expires, userId));
    }

    /**
     * 校验签名与有效期。
     *
     * @param nowSeconds 当前秒级时间戳；显式传入而非内部取系统时间，便于单测构造过期场景
     * @return 签名不匹配或已过期返回 false
     */
    public boolean verify(String path, long expires, Long userId, String sign, long nowSeconds) {
        if (sign == null || sign.isEmpty() || nowSeconds > expires) {
            return false;
        }
        // 常量时间比较：先比十六进制串，避免为解析十六进制再多写一段易错的代码
        byte[] expected = sign(path, expires, userId).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = sign.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] hmac(String path, long expires, Long userId) {
        String payload = path + "|" + expires + "|" + (userId == null ? GUEST_ID : userId);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // HmacSHA256 是 JDK 必备算法，走到这里说明运行环境异常，直接失败让调用方感知
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    private static String encodeHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
