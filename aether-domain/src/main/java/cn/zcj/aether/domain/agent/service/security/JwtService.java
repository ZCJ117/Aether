package cn.zcj.aether.domain.agent.service.security;

import cn.zcj.aether.types.enums.UserRole;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * P0: JWT 令牌服务。
 *
 * <p>提供 Access Token（HMAC-SHA256，15min TTL）和
 * Refresh Token（UUID，7天 TTL）的签发、校验和刷新。
 *
 * <p>密钥通过 {@code aether.security.jwt.secret} 配置（经 jasypt 解密）。
 */
@Slf4j
@Service
public class JwtService {

    /** HMAC 签名密钥 */
    private final SecretKey signingKey;

    /** Access Token 过期时间（毫秒），默认 15 分钟 */
    private final long accessTokenExpirationMs;

    /** Refresh Token 过期时间（毫秒），默认 7 天 */
    private final long refreshTokenExpirationMs;

    public JwtService(
            @Value("${aether.security.jwt.secret}") String jwtSecret,
            @Value("${aether.security.jwt.access-token-expiration:900000}") long accessTokenExpirationMs,
            @Value("${aether.security.jwt.refresh-token-expiration:604800000}") long refreshTokenExpirationMs) {
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 characters");
        }
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationMs = accessTokenExpirationMs;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
    }

    public String generateAccessToken(Long userId, String username, UserRole role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role.getCode())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenExpirationMs))
                .signWith(signingKey)
                .compact();
    }

    public String generateRefreshToken() {
        return UUID.randomUUID().toString();
    }

    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserId(String token) {
        return Long.parseLong(validateToken(token).getSubject());
    }

    public String getUsername(String token) {
        return validateToken(token).get("username", String.class);
    }

    public String getRole(String token) {
        return validateToken(token).get("role", String.class);
    }

    public long getAccessTokenExpirationMs() {
        return accessTokenExpirationMs;
    }

    public long getRefreshTokenExpirationMs() {
        return refreshTokenExpirationMs;
    }
}
