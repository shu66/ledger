package com.surf.ledger.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;

/** 管理员权限每次读库校验：降权、停用、登出立即生效，不等 JWT 过期 */
@Component
public class AdminInterceptor implements HandlerInterceptor {
    private final TokenService tokens;
    private final JdbcTemplate jdbc;

    public AdminInterceptor(TokenService tokens, JdbcTemplate jdbc) {
        this.tokens = tokens;
        this.jdbc = jdbc;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        Map<String, Object> claims = AuthInterceptor.extract(tokens, req);
        if (claims == null) return AuthInterceptor.reject(res, 401, "UNAUTHORIZED");

        long uid = (Long) claims.get("uid");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, status, token_version FROM users WHERE id = ?", uid);
        if (rows.isEmpty()) return AuthInterceptor.reject(res, 401, "UNAUTHORIZED");

        Map<String, Object> u = rows.get(0);
        if (!"active".equals(u.get("status"))) {
            return AuthInterceptor.reject(res, 401, "ACCOUNT_DISABLED");
        }

        int issued = (Integer) claims.getOrDefault("ver", 0);
        int current = ((Number) u.get("token_version")).intValue();
        if (issued != current) return AuthInterceptor.reject(res, 401, "TOKEN_REVOKED");

        if (!"admin".equals(u.get("role"))) {
            return AuthInterceptor.reject(res, 403, "FORBIDDEN");
        }

        req.setAttribute(AuthInterceptor.UID, uid);
        req.setAttribute(AuthInterceptor.ROLE, "admin");
        return true;
    }
}
