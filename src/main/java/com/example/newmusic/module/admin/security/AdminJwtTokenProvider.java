package com.example.newmusic.module.admin.security;

import cn.hutool.core.util.IdUtil;
import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.security.RsaKeyLoader;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 后台 JWT：独立密钥 + 独立 issuer + 会话版本号。
 *
 * <p>{@code sv}（session version）是「禁用账号/改密/强退立即生效」的载体：
 * 过滤器每次请求拿它和 Redis 中的当前版本比对。JWT 本身仍是 1 小时有效期，
 * 但被踢的管理员在下一个请求就会收到 401（而不是等一小时）。
 */
@Slf4j
@Component
public class AdminJwtTokenProvider {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_NICKNAME = "nickname";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMS = "perms";
    private static final String CLAIM_SV = "sv";

    private final AdminJwtProperties properties;
    private final RsaKeyLoader keyLoader;

    private PrivateKey privateKey;
    private PublicKey publicKey;

    public AdminJwtTokenProvider(AdminJwtProperties properties, RsaKeyLoader keyLoader) {
        this.properties = properties;
        this.keyLoader = keyLoader;
    }

    @PostConstruct
    public void init() {
        this.privateKey = keyLoader.loadPrivateKey(properties.getPrivateKeyLocation());
        this.publicKey = keyLoader.loadPublicKey(properties.getPublicKeyLocation());
        log.info("后台 JWT 密钥加载完成，issuer={}, accessTtl={}s, refreshTtl={}s",
                properties.getIssuer(), properties.getAccessTokenTtl(), properties.getRefreshTokenTtl());
    }

    public String createAccessToken(LoginUser admin, long sessionVersion) {
        return Jwts.builder()
                .setIssuer(properties.getIssuer())
                .setSubject(String.valueOf(admin.getUserId()))
                .setId(IdUtil.fastSimpleUUID())
                .setIssuedAt(new Date())
                .setExpiration(expireAt(properties.getAccessTokenTtl()))
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim(CLAIM_USERNAME, admin.getUsername())
                .claim(CLAIM_NICKNAME, admin.getNickname())
                .claim(CLAIM_ROLES, new ArrayList<>(nullSafe(admin.getRoles())))
                .claim(CLAIM_PERMS, new ArrayList<>(nullSafe(admin.getPerms())))
                .claim(CLAIM_SV, sessionVersion)
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }

    public String createRefreshToken(Long adminId, String tokenId, long sessionVersion) {
        return Jwts.builder()
                .setIssuer(properties.getIssuer())
                .setSubject(String.valueOf(adminId))
                .setId(tokenId)
                .setIssuedAt(new Date())
                .setExpiration(expireAt(properties.getRefreshTokenTtl()))
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .claim(CLAIM_SV, sessionVersion)
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }

    public Claims parse(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .requireIssuer(properties.getIssuer())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return claims;
        } catch (ExpiredJwtException e) {
            throw new BizException(ResultCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("后台 Token 解析失败: {}", e.getMessage());
            throw new BizException(ResultCode.TOKEN_INVALID);
        }
    }

    public LoginUser toLoginUser(Claims claims) {
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            return null;
        }
        return LoginUser.builder()
                .userId(Long.valueOf(claims.getSubject()))
                .username(claims.get(CLAIM_USERNAME, String.class))
                .nickname(claims.get(CLAIM_NICKNAME, String.class))
                .userType(3)
                .roles(toStringSet(claims.get(CLAIM_ROLES)))
                .perms(toStringSet(claims.get(CLAIM_PERMS)))
                .build();
    }

    public boolean isRefreshToken(Claims claims) {
        return TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public Long getAdminId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    public String getTokenId(Claims claims) {
        return claims.getId();
    }

    public long getSessionVersion(Claims claims) {
        Number value = claims.get(CLAIM_SV, Number.class);
        return value == null ? 0L : value.longValue();
    }

    public long getRemainingSeconds(Claims claims) {
        Date expiration = claims.getExpiration();
        if (expiration == null) {
            return 0;
        }
        long remaining = expiration.getTime() - System.currentTimeMillis();
        return remaining <= 0 ? 0 : remaining / 1000;
    }

    public long getAccessTokenTtl() {
        return properties.getAccessTokenTtl();
    }

    public long getRefreshTokenTtl() {
        return properties.getRefreshTokenTtl();
    }

    private Date expireAt(long ttlSeconds) {
        return new Date(System.currentTimeMillis() + ttlSeconds * 1000L);
    }

    private Set<String> nullSafe(Set<String> set) {
        return set == null ? Collections.emptySet() : set;
    }

    @SuppressWarnings("unchecked")
    private Set<String> toStringSet(Object value) {
        if (value instanceof List) {
            return new LinkedHashSet<>((List<String>) value);
        }
        return Collections.emptySet();
    }
}
