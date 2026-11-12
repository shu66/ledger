package com.surf.ledger.admin;

import com.surf.ledger.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

@Service
public class AdminService {
    private static final SecureRandom RNG = new SecureRandom();
    private static final String ALPHABET =
            "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public AdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> users(String q, int limit) {
        String like = (q == null || q.isBlank()) ? "%" : "%" + q.trim().toLowerCase() + "%";
        return jdbc.queryForList("""
                SELECT u.id, u.email, u.nickname, u.role, u.status,
                       u.created_at, u.last_login_at,
                       EXISTS(SELECT 1 FROM user_identities i
                              WHERE i.user_id = u.id AND i.provider = 'wechat') AS wx_bound,
                       (SELECT COUNT(*) FROM transactions t
                        WHERE t.user_id = u.id AND t.deleted = 0) AS tx_count
                FROM users u
                WHERE LOWER(u.email) LIKE ? OR LOWER(COALESCE(u.nickname,'')) LIKE ?
                ORDER BY u.id DESC
                LIMIT ?
                """, like, like, limit);
    }

    @Transactional
    public void patchUser(long actorId, long id, String role, String status) {
        if (id == actorId && (("user".equals(role)) || ("disabled".equals(status)))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_DEMOTE_SELF");
        }
        if (role != null && !role.equals("user") && !role.equals("admin")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ROLE");
        }
        if (status != null && !status.equals("active") && !status.equals("disabled")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_STATUS");
        }

        int n = jdbc.update("""
                UPDATE users SET role = COALESCE(?, role), status = COALESCE(?, status)
                WHERE id = ?
                """, role, status, id);
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    @Transactional
    public String resetPassword(long id) {
        String temp = tempPassword(12);
        int n = jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?",
                bcrypt.encode(temp), id);
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        return temp;
    }

    public Map<String, Object> stats() {
        return jdbc.queryForMap("""
                SELECT
                  (SELECT COUNT(*) FROM users) AS users,
                  (SELECT COUNT(*) FROM users WHERE status = 'disabled') AS disabled_users,
                  (SELECT COUNT(*) FROM user_identities WHERE provider = 'wechat') AS wx_bound,
                  (SELECT COUNT(*) FROM transactions WHERE deleted = 0) AS tx_count,
                  (SELECT COALESCE(SUM(amount),0) FROM transactions
                    WHERE deleted = 0 AND type = 'expense'
                      AND occurred_at >= DATE_SUB(CURDATE(), INTERVAL DAYOFMONTH(CURDATE()) - 1 DAY)
                  ) AS month_expense_fen
                """);
    }

    public List<Map<String, Object>> auditLogs(int limit) {
        return jdbc.queryForList("""
                SELECT a.id, a.action, a.target, a.detail, a.ip, a.created_at, u.email AS actor
                FROM audit_logs a LEFT JOIN users u ON u.id = a.actor_id
                ORDER BY a.id DESC LIMIT ?
                """, limit);
    }

    public void audit(Long actorId, String action, String target, String detail, String ip) {
        jdbc.update("""
                INSERT INTO audit_logs(actor_id, action, target, detail, ip)
                VALUES (?,?,?,?,?)
                """, actorId, action, target, detail, ip);
    }

    private String tempPassword(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
