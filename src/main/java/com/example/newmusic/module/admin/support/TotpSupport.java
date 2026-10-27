package com.example.newmusic.module.admin.support;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * TOTP（RFC 6238，SHA-1 / 6 位 / 30 秒），不引入第三方 OTP 依赖。
 *
 * <p>允许 ±1 个时间步以容忍客户端与服务端的少量时钟漂移（PRD FR-18-001）。
 */
@Component
public class TotpSupport {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 校验动态码。secret 为空或格式非法时返回 false。 */
    public boolean verify(String secret, String code) {
        if (secret == null || code == null || !code.matches("\\d{6}")) {
            return false;
        }
        byte[] key;
        try {
            key = decodeBase32(secret);
        } catch (IllegalArgumentException e) {
            return false;
        }
        long counter = System.currentTimeMillis() / 1000L / STEP_SECONDS;
        for (int offset = -1; offset <= 1; offset++) {
            if (constantEquals(generate(key, counter + offset), code)) {
                return true;
            }
        }
        return false;
    }

    /** 生成新密钥（160 bit，Base32 无填充）。管理端「重置 TOTP」用。 */
    public String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    /** 生成当前动态码；仅用于演示脚本与单元排查，生产不在接口暴露。 */
    public String currentCode(String secret) {
        return generate(decodeBase32(secret), System.currentTimeMillis() / 1000L / STEP_SECONDS);
    }

    private String generate(byte[] key, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            return String.format(Locale.ROOT, "%0" + DIGITS + "d", binary % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP 生成失败", e);
        }
    }

    public static String encodeBase32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(BASE32.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return sb.toString();
    }

    public static byte[] decodeBase32(String value) {
        String normalized = value.replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (char c : normalized.toCharArray()) {
            int idx = BASE32.indexOf(c);
            if (idx < 0) {
                throw new IllegalArgumentException("非法的 Base32 字符: " + c);
            }
            buffer = (buffer << 5) | idx;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    private boolean constantEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
