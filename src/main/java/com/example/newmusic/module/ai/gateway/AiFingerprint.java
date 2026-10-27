package com.example.newmusic.module.ai.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 请求指纹：{@code sha256(model + promptVersion + system + user + temperature + maxTokens + jsonMode)}。
 *
 * <p><b>模型与 Prompt 版本必须在指纹里</b>（BR-23-014）。否则模型升级后仍会读到旧缓存，
 * 「升级」在用户侧看起来毫无变化，而团队会以为已经生效——这类问题很难靠日志发现。
 */
public final class AiFingerprint {

    private AiFingerprint() {
    }

    public static String of(String model, String promptVersion, String systemPrompt,
                            String userPrompt, Double temperature, Integer maxTokens,
                            boolean jsonMode) {
        String raw = String.join("\u0001",
                nullSafe(model),
                nullSafe(promptVersion),
                nullSafe(systemPrompt),
                nullSafe(userPrompt),
                temperature == null ? "0.7" : String.valueOf(temperature),
                maxTokens == null ? "" : String.valueOf(maxTokens),
                String.valueOf(jsonMode));
        return sha256(raw);
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JVM 必须提供 SHA-256；到这里说明运行环境已经不可信
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
