package com.example.newmusic.module.admin.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 后台 JWT / 会话配置。
 *
 * <p>与 C 端 {@code jwt.*} 刻意分成两套：密钥不同、issuer 不同（{@code ml-admin}），
 * 因此任何一侧的 Token 都无法在另一侧通过签名或 issuer 校验（FR-18-001 / D-01）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin-jwt")
public class AdminJwtProperties {

    private String privateKeyLocation = "classpath:jwt/admin_private_key.pem";
    private String publicKeyLocation = "classpath:jwt/admin_public_key.pem";

    /** 必须是 C 端 issuer 之外的值 */
    private String issuer = "ml-admin";

    /** accessToken 1 小时（PRD FR-18-001） */
    private long accessTokenTtl = 3600L;

    /** refreshToken 8 小时 */
    private long refreshTokenTtl = 28800L;

    /** Cookie 名 */
    private String accessCookie = "ml_admin_access";
    private String refreshCookie = "ml_admin_refresh";

    /** 生产必须 true（HTTPS） */
    private boolean cookieSecure = false;

    /** 后台允许的 Origin（CSRF 校验用），逗号分隔 */
    private String allowedOrigins = "http://localhost:5174,http://127.0.0.1:5174";
}
