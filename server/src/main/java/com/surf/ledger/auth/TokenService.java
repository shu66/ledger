package com.surf.ledger.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class TokenService {
    public static final String ACCESS = "access";
    public static final String BIND = "bind";

    private final SecretKey key;
    private final long ttlMillis;
    private final long bindTtlMillis;

    public TokenService(@Value("${jwt.secret:}") String secret,
                        @Value("${jwt.ttl-days}") long ttlDays,
                        @Value("${jwt.bind-ttl-minutes}") long bindMinutes) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET 没配置或太短（HS256 至少 32 字节）。"
                            + "在 server/.env 里写 JWT_SECRET=...（openssl rand -hex 32），"
                            + "或设置同名环境变量。");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMillis = ttlDays * 86_400_000L;
        this.bindTtlMillis = bindMinutes * 60_000L;
    }

    public String issueAccess(long uid, String role, int tokenVersion) {
        return build(uid, ACCESS, ttlMillis)
                .claim("role", role)
                .claim("ver", tokenVersion)
                .compact();
    }

    /** 微信未绑定时发出的短期票据，只能用于绑定 */
    public String issueBindToken(String openid) {
        return build(null, BIND, bindTtlMillis).claim("openid", openid).compact();
    }

    private io.jsonwebtoken.JwtBuilder build(Long uid, String typ, long ttl) {
        long now = System.currentTimeMillis();
        var b = Jwts.builder()
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttl))
                .claim("typ", typ);
        if (uid != null) b.subject(String.valueOf(uid));
        return b.signWith(key);
    }

    public Claims parse(String token, String expectedTyp) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            if (!expectedTyp.equals(c.get("typ", String.class))) return null;
            return c;
        } catch (Exception e) {
            return null;
        }
    }
}
