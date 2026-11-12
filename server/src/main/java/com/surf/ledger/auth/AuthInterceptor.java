package com.surf.ledger.auth;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 访问令牌校验：签名之外还读库确认「账号仍启用、令牌版本未被吊销」，
 * 因此停用用户和已登出的旧 token 都立即失效，不用等 30 天过期。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String UID = "uid";
    public static final String ROLE = "role";

    private final TokenService tokens;
    private final JdbcTemplate jdbc;

    public AuthInterceptor(TokenService tokens, JdbcTemplate jdbc) {
        this.tokens = tokens;
        this.jdbc = jdbc;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        Map<String, Object> claims = extract(tokens, req);
        if (claims == null) return reject(res, 401, "UNAUTHORIZED");

        long uid = (Long) claims.get("uid");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, status, token_version FROM users WHERE id = ?", uid);
        if (rows.isEmpty()) return reject(res, 401, "UNAUTHORIZED");

        Map<String, Object> u = rows.get(0);
        if (!"active".equals(u.get("status"))) return reject(res, 401, "ACCOUNT_DISABLED");

        int issued = (Integer) claims.getOrDefault("ver", 0);
        int current = ((Number) u.get("token_version")).intValue();
        if (issued != current) return reject(res, 401, "TOKEN_REVOKED");

        req.setAttribute(UID, uid);
        // 角色以库为准：管理后台降权后立即失去 admin 能力
        req.setAttribute(ROLE, u.get("role"));
        return true;
    }

    static Map<String, Object> extract(TokenService tokens, HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        String token = (h != null && h.startsWith("Bearer ")) ? h.substring(7) : "";
        Claims c = tokens.parse(token, TokenService.ACCESS);
        if (c == null || c.getSubject() == null) return null;

        Map<String, Object> m = new HashMap<>();
        m.put("uid", Long.valueOf(c.getSubject()));
        String role = c.get("role", String.class);
        m.put("role", role == null ? "user" : role);
        Number ver = c.get("ver", Number.class);
        m.put("ver", ver == null ? 0 : ver.intValue());
        return m;
    }

    static boolean reject(HttpServletResponse res, int status, String code) {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        try {
            res.getWriter().write("{\"error\":\"" + code + "\"}");
        } catch (IOException ignored) {
            // 连接已断开时忽略
        }
        return false;
    }
}
