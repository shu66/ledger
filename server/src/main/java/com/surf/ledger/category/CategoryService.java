package com.surf.ledger.category;

import com.surf.ledger.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

@Service
public class CategoryService {
    private static final int MAX_NAME = 12;
    private static final SecureRandom RND = new SecureRandom();

    private final JdbcTemplate jdbc;

    public CategoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Category(String id, String name, String type, String icon, boolean custom) {}

    /** 系统分类 + 当前用户自建分类，系统在前 */
    public List<Category> list(long uid) {
        return jdbc.query("""
                SELECT id, name, type, icon, user_id
                FROM categories
                WHERE user_id IS NULL OR user_id = ?
                ORDER BY (user_id IS NULL) DESC, type, sort, id
                """,
                (rs, i) -> new Category(rs.getString("id"), rs.getString("name"),
                        rs.getString("type"), rs.getString("icon"),
                        rs.getObject("user_id") != null),
                uid);
    }

    /**
     * 新建分类；同名（系统或自己的）直接返回已有的，方便 AI 反复建议同一个名字。
     * 失败（名字非法等）由调用方决定要不要吞掉。
     */
    public Category create(long uid, String name, String type, String icon) {
        String n = normalize(name);
        if (!"expense".equals(type) && !"income".equals(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_CATEGORY_TYPE");
        }
        String ic = (icon == null || icon.isBlank())
                ? ("income".equals(type) ? "💵" : "✨")
                : icon.trim();

        List<Category> exist = jdbc.query("""
                        SELECT id, name, type, icon, user_id FROM categories
                        WHERE name = ? AND (user_id IS NULL OR user_id = ?)
                        LIMIT 1
                        """,
                (rs, i) -> new Category(rs.getString("id"), rs.getString("name"),
                        rs.getString("type"), rs.getString("icon"),
                        rs.getObject("user_id") != null),
                n, uid);
        if (!exist.isEmpty()) return exist.get(0);

        for (int attempt = 0; attempt < 5; attempt++) {
            String id = newId(uid);
            try {
                jdbc.update("""
                        INSERT INTO categories(id, user_id, name, type, icon, sort)
                        VALUES (?,?,?,?,?,100)
                        """, id, uid, n, type, ic);
                return new Category(id, n, type, ic, true);
            } catch (DuplicateKeyException e) {
                // 要么 id 撞了，要么另一个请求刚插了同名分类
                List<Category> again = jdbc.query("""
                                SELECT id, name, type, icon, user_id FROM categories
                                WHERE user_id = ? AND name = ? LIMIT 1
                                """,
                        (rs, i) -> new Category(rs.getString("id"), rs.getString("name"),
                                rs.getString("type"), rs.getString("icon"), true),
                        uid, n);
                if (!again.isEmpty()) return again.get(0);
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "CATEGORY_CREATE_FAILED");
    }

    /** 解析时 AI 建议的新分类：不合法的名字吞掉，不影响记账 */
    public String ensureQuietly(long uid, String name, String type) {
        try {
            return create(uid, name, type, null).id();
        } catch (Exception e) {
            return null;
        }
    }

    public void delete(long uid, String id) {
        int n = jdbc.update("DELETE FROM categories WHERE id = ? AND user_id = ?", id, uid);
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND");
    }

    private String normalize(String name) {
        if (name == null) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_CATEGORY_NAME");
        String n = name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty() || n.length() > MAX_NAME) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_CATEGORY_NAME");
        }
        return n;
    }

    private String newId(long uid) {
        byte[] b = new byte[4];
        RND.nextBytes(b);
        StringBuilder sb = new StringBuilder("u").append(uid).append('_');
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
