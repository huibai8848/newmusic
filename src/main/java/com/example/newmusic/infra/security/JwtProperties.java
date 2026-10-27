package com.example.newmusic.infra.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置（RS256，NFR-S-003）。
 *
 * <p>DEV 使用仓库内 {@code resources/jwt/*.pem}；生产私钥由 KMS/密钥挂载注入，
 * 通过 {@code jwt.private-key-location} 指向容器内路径。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** 私钥位置，支持 classpath: 与 file: 前缀 */
    private String privateKeyLocation = "classpath:jwt/private_key.pem";

    /** 公钥位置 */
    private String publicKeyLocation = "classpath:jwt/public_key.pem";

    private String issuer = "newmusic";

    /** accessToken 有效期（秒），默认 30 分钟 */
    private long accessTokenTtl = 1800L;

    /** refreshToken 有效期（秒），默认 7 天 */
    private long refreshTokenTtl = 604800L;
}
