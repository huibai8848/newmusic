package com.example.newmusic.infra.security;

import cn.hutool.core.util.IdUtil;
import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.security.PermissionCatalogue;
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
 * JWT 令牌签发与校验（RS256）。
 *
 * <p>accessToken 内嵌 roles / perms，服务端 {@code @PreAuthorize} 二次校验。
 *
 * <p><b>模块 03 没有按原计划把权限搬进 Redis</b>（系分 D-24「Token 瘦身」）。理由：
 * 搬走之后每个请求都要多一次 Redis 往返，和模块 03 的降级面叠加；而「封禁/登出即时生效」
 * 这个真正需要即时性的需求，已经由 {@code ml:auth:userban:} 与
 * {@code ml:auth:blacklist:} 独立覆盖，不依赖权限搬家。等真出现「权限需即时变更」的
 * 业务诉求时再做，那时才付得起这个代价。
 */
@Slf4j
@Component
public class JwtTokenProvider {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_NICKNAME = "nickname";
    private static final String CLAIM_USER_TYPE = "userType";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMS = "perms";

    private final JwtProperties properties;
    private final RsaKeyLoader keyLoader;

    private PrivateKey privateKey;
    private PublicKey publicKey;

    public JwtTokenProvider(JwtProperties properties, RsaKeyLoader keyLoader) {
        this.properties = properties;
        this.keyLoader = keyLoader;
    }

    @PostConstruct
    public void init() {
        this.privateKey = keyLoader.loadPrivateKey(properties.getPrivateKeyLocation());
        this.publicKey = keyLoader.loadPublicKey(properties.getPublicKeyLocation());
        log.info("JWT 密钥加载完成，issuer={}, accessTtl={}s, refreshTtl={}s",
                properties.getIssuer(), properties.getAccessTokenTtl(), properties.getRefreshTokenTtl());
    }

    public String createAccessToken(LoginUser user) {
        return Jwts.builder()
                .setIssuer(properties.getIssuer())
                .setSubject(String.valueOf(user.getUserId()))
                .setId(IdUtil.fastSimpleUUID())
                .setIssuedAt(new Date())
                .setExpiration(expireAt(properties.getAccessTokenTtl()))
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_NICKNAME, user.getNickname())
                .claim(CLAIM_USER_TYPE, user.getUserType())
                .claim(CLAIM_ROLES, new ArrayList<>(nullSafe(user.getRoles())))
                .claim(CLAIM_PERMS, new ArrayList<>(nullSafe(user.getPerms())))
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }

    /**
     * refreshToken 只带 userId，最小权限面。
     *
     * <p>{@code tokenId} 由调用方传入（模块 03）：它是 Redis 里那份「一次性账本」的键，
     * 签发方需要在把 Token 交给用户之前就把这条账目记下来，所以必须由签发方决定这个 ID，
     * 而不是签发完了再从 Token 里反解出来。
     */
    public String createRefreshToken(Long userId, String tokenId) {
        return Jwts.builder()
                .setIssuer(properties.getIssuer())
                .setSubject(String.valueOf(userId))
                .setId(tokenId)
                .setIssuedAt(new Date())
                .setExpiration(expireAt(properties.getRefreshTokenTtl()))
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }

    public long getAccessTokenTtl() {
        return properties.getAccessTokenTtl();
    }

    public long getRefreshTokenTtl() {
        return properties.getRefreshTokenTtl();
    }

    /** JWT 的 {@code jti}。模块 03 用它作为黑名单键与 refresh 账本键 */
    public String getTokenId(Claims claims) {
        return claims.getId();
    }

    /**
     * Token 的剩余有效秒数。
     *
     * <p>黑名单只需要留住「本来还有效的那段时间」：过期之后 JWT 自己就会被拒，
     * 再占着键只是白白消耗内存。所以吊销时用这个值当 TTL，而不是固定值。
     *
     * @return 已过期或无法取到过期时间时返回 0
     */
    public long getRemainingSeconds(Claims claims) {
        Date expiration = claims.getExpiration();
        if (expiration == null) {
            return 0;
        }
        long remainingMillis = expiration.getTime() - System.currentTimeMillis();
        return remainingMillis <= 0 ? 0 : remainingMillis / 1000;
    }

    /**
     * 校验并解析。失败抛 40103（过期）/40102（非法）。
     */
    public Claims parse(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            throw new BizException(ResultCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Token 解析失败: {}", e.getMessage());
            throw new BizException(ResultCode.TOKEN_INVALID);
        }
    }

    /** accessToken → LoginUser；refreshToken 不适用（返回 null）。 */
    public LoginUser toLoginUser(Claims claims) {
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            return null;
        }
        return LoginUser.builder()
                .userId(Long.valueOf(claims.getSubject()))
                .username(claims.get(CLAIM_USERNAME, String.class))
                .nickname(claims.get(CLAIM_NICKNAME, String.class))
                .userType(claims.get(CLAIM_USER_TYPE, Integer.class))
                .roles(toStringSet(claims.get(CLAIM_ROLES)))
                // 兼容历史 Token：旧 Token 可能携带未展开的通配码
                .perms(PermissionCatalogue.expand(toStringSet(claims.get(CLAIM_PERMS))))
                .build();
    }

    public boolean isRefreshToken(Claims claims) {
        return TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public Long getUserId(Claims claims) {
        return Long.valueOf(claims.getSubject());
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
