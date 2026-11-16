package com.surf.ledger.auth;

import com.surf.ledger.common.ApiException;
import com.surf.ledger.common.Crypto;
import com.surf.ledger.wx.WxService;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TokenService tokens;
    private final WxService wx;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @Value("${app.admin-email:}")
    private String adminEmail;

    @Value("${app.signup-enabled:true}")
    private boolean signupEnabled;

    @Value("${app.public-url}")
    private String publicUrl;

    public AuthService(JdbcTemplate jdbc, TokenService tokens, WxService wx) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.wx = wx;
    }

    // ---------- 邮箱 ----------

    @Transactional
    public Map<String, Object> register(String email, String password, String nickname) {
        String mail = normalizeEmail(email);
        validate(password);
        NewUser u = createUser(mail, password, nickname);
        return tokenPayload(u.id(), u.role());
    }

    /** 注册与「微信注册并绑定」共用的建号逻辑 */
    private NewUser createUser(String mail, String password, String nickname) {
        Long exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Long.class, mail);
        if (exists != null && exists > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN");
        }

        long total = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        boolean bootstrap = total == 0;
        if (!bootstrap && !signupEnabled) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SIGNUP_DISABLED");
        }

        boolean isAdmin = bootstrap
                || (!adminEmail.isBlank() && adminEmail.equalsIgnoreCase(mail));

        String nick = (nickname == null || nickname.isBlank())
                ? mail.split("@")[0] : nickname.trim();
        String hash = bcrypt.encode(password);
        String role = isAdmin ? "admin" : "user";

        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO users(email, password_hash, nickname, role) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, mail);
            ps.setString(2, hash);
            ps.setString(3, nick);
            ps.setString(4, role);
            return ps;
        }, kh);

        return new NewUser(kh.getKey().longValue(), role);
    }

    private record NewUser(long id, String role) {}

    public Map<String, Object> login(String email, String password) {
        String mail = normalizeEmail(email);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, password_hash, role, status FROM users WHERE email = ?", mail);

        if (rows.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS");

        Map<String, Object> u = rows.get(0);
        if (!bcrypt.matches(password == null ? "" : password, (String) u.get("password_hash"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS");
        }
        if ("disabled".equals(u.get("status"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }

        long id = ((Number) u.get("id")).longValue();
        jdbc.update("UPDATE users SET last_login_at = NOW(3) WHERE id = ?", id);
        return tokenPayload(id, (String) u.get("role"));
    }

    // ---------- 微信 ----------

    public Map<String, Object> wxLogin(String code) {
        String openid = wx.openid(code);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.id, u.role, u.status
                FROM user_identities i JOIN users u ON u.id = i.user_id
                WHERE i.provider = 'wechat' AND i.external_id = ?
                """, openid);

        if (rows.isEmpty()) {
            return Map.of("needBind", true, "bindToken", tokens.issueBindToken(openid));
        }

        Map<String, Object> u = rows.get(0);
        if ("disabled".equals(u.get("status"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }
        long id = ((Number) u.get("id")).longValue();
        jdbc.update("UPDATE users SET last_login_at = NOW(3) WHERE id = ?", id);
        return tokenPayload(id, (String) u.get("role"));
    }

    @Transactional
    public Map<String, Object> bindWx(String bindToken, String email, String password) {        Claims c = tokens.parse(bindToken, TokenService.BIND);
        if (c == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "BIND_TOKEN_INVALID");
        String openid = c.get("openid", String.class);

        String mail = normalizeEmail(email);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, password_hash, role, status FROM users WHERE email = ?", mail);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS");

        Map<String, Object> u = rows.get(0);
        if (!bcrypt.matches(password == null ? "" : password, (String) u.get("password_hash"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS");
        }
        if ("disabled".equals(u.get("status"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }

        long uid = ((Number) u.get("id")).longValue();

        List<Map<String, Object>> bound = jdbc.queryForList("""
                SELECT user_id FROM user_identities
                WHERE provider = 'wechat' AND external_id = ?
                """, openid);

        if (!bound.isEmpty() && ((Number) bound.get(0).get("user_id")).longValue() != uid) {
            throw new ApiException(HttpStatus.CONFLICT, "WECHAT_ALREADY_BOUND");
        }
        if (bound.isEmpty()) {
            jdbc.update("""
                    INSERT INTO user_identities(user_id, provider, external_id)
                    VALUES (?, 'wechat', ?)
                    """, uid, openid);
        }

        jdbc.update("UPDATE users SET last_login_at = NOW(3) WHERE id = ?", uid);
        return tokenPayload(uid, (String) u.get("role"));
    }

    /**
     * 微信首次登录：一次完成「建账号 + 绑微信 + 发令牌」。
     * 之前是客户端先 /auth/register 再 /auth/wx/bind，两次调用之间一旦
     * 绑定票据过期（10 分钟）或第二步失败，就会出现「注册成功但微信没绑上」。
     */
    @Transactional
    public Map<String, Object> wxRegister(String bindToken, String email, String password, String nickname) {
        Claims c = tokens.parse(bindToken, TokenService.BIND);
        if (c == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "BIND_TOKEN_INVALID");
        String openid = c.get("openid", String.class);

        String mail = normalizeEmail(email);
        validate(password);

        // 同一个微信已经绑过账号：密码一致就当作登录，不再重复建号
        List<Map<String, Object>> bound = jdbc.queryForList("""
                SELECT u.id, u.email, u.role, u.status
                FROM user_identities i JOIN users u ON u.id = i.user_id
                WHERE i.provider = 'wechat' AND i.external_id = ?
                """, openid);

        if (!bound.isEmpty()) {
            Map<String, Object> u = bound.get(0);
            if (!mail.equalsIgnoreCase(String.valueOf(u.get("email")))) {
                throw new ApiException(HttpStatus.CONFLICT, "WECHAT_ALREADY_BOUND");
            }
            if ("disabled".equals(u.get("status"))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
            }
            long uid0 = ((Number) u.get("id")).longValue();
            jdbc.update("UPDATE users SET last_login_at = NOW(3) WHERE id = ?", uid0);
            return tokenPayload(uid0, (String) u.get("role"));
        }

        NewUser u = createUser(mail, password, nickname);

        jdbc.update("""
                INSERT INTO user_identities(user_id, provider, external_id)
                VALUES (?, 'wechat', ?)
                """, u.id(), openid);
        jdbc.update("UPDATE users SET last_login_at = NOW(3) WHERE id = ?", u.id());

        return tokenPayload(u.id(), u.role());
    }

    @Transactional
    public void unbindWx(long uid) {
        jdbc.update("DELETE FROM user_identities WHERE user_id = ? AND provider = 'wechat'", uid);
    }

    /** 登出：自增令牌版本，已发出的访问令牌立刻失效 */
    public void logout(long uid) {
        jdbc.update("UPDATE users SET token_version = token_version + 1 WHERE id = ?", uid);
    }

    // ---------- 口令 ----------

    public void requestReset(String email) {
        String mail = normalizeEmail(email);
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM users WHERE email = ? AND status = 'active'", Long.class, mail);

        if (ids.isEmpty()) return;

        String raw = randomToken();
        jdbc.update("""
                INSERT INTO auth_tokens(user_id, purpose, token_hash, expires_at)
                VALUES (?, 'reset_password', ?, DATE_ADD(NOW(3), INTERVAL 30 MINUTE))
                """, ids.get(0), Crypto.sha256(raw));

        // 未接 SMTP 时打印到日志
        log.info("PASSWORD RESET LINK: {}/login.html#reset={}", publicUrl, raw);
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD");
        }
        String hash = Crypto.sha256(token == null ? "" : token);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, user_id FROM auth_tokens
                WHERE token_hash = ? AND purpose = 'reset_password'
                  AND used_at IS NULL AND expires_at > NOW(3)
                """, hash);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "RESET_TOKEN_INVALID");

        long tokenId = ((Number) rows.get(0).get("id")).longValue();
        long uid = ((Number) rows.get(0).get("user_id")).longValue();

        // 改密同时吊销所有旧令牌
        jdbc.update("""
                UPDATE users SET password_hash = ?, token_version = token_version + 1
                WHERE id = ?
                """, bcrypt.encode(newPassword), uid);
        jdbc.update("UPDATE auth_tokens SET used_at = NOW(3) WHERE id = ?", tokenId);
    }

    // ---------- 通用 ----------

    public Map<String, Object> me(long uid) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.id, u.email, u.nickname, u.role,
                       EXISTS(SELECT 1 FROM user_identities i
                              WHERE i.user_id = u.id AND i.provider = 'wechat') AS wx_bound
                FROM users u WHERE u.id = ?
                """, uid);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");

        Map<String, Object> r = rows.get(0);
        Object bound = r.get("wx_bound");
        boolean wxBound = bound instanceof Number && ((Number) bound).intValue() == 1;

        return Map.of(
                "id", ((Number) r.get("id")).longValue(),
                "email", r.get("email"),
                "nickname", r.get("nickname") == null ? "" : r.get("nickname"),
                "role", r.get("role"),
                "wxBound", wxBound
        );
    }

    private Map<String, Object> tokenPayload(long uid, String role) {
        Integer ver = jdbc.queryForObject(
                "SELECT token_version FROM users WHERE id = ?", Integer.class, uid);
        return Map.of("token", tokens.issueAccess(uid, role, ver == null ? 0 : ver), "role", role);
    }

    private String normalizeEmail(String email) {
        if (email == null) throw new ApiException(HttpStatus.BAD_REQUEST, "EMAIL_REQUIRED");
        String e = email.trim().toLowerCase();
        if (!e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMAIL_INVALID");
        }
        return e;
    }

    private void validate(String password) {
        if (password == null || password.length() < 8) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD");
        }
        if (password.length() > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG");
        }
    }

    private String randomToken() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
