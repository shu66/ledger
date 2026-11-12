package com.surf.ledger.tx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surf.ledger.ai.ParseDtos.Item;
import com.surf.ledger.common.ApiException;
import com.surf.ledger.tx.TxDtos.SummaryRow;
import com.surf.ledger.tx.TxDtos.TxRow;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TxService {
    /** 库里存的是 Asia/Shanghai 的 DATETIME，两端都在这一时区换算 */
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public TxService(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @Transactional
    public List<Long> insertBatch(long uid, List<Item> items, String text,
                                  String raw, String model, boolean edited, String source) {
        List<Long> ids = new ArrayList<>();
        String src = "manual".equals(source) ? "manual" : "text";

        for (Item it : items) {
            if (it.amount() == null || it.amount() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "AMOUNT_INVALID");
            }
            LocalDateTime at;
            try {
                at = OffsetDateTime.parse(it.occurredAt())
                        .atZoneSameInstant(CN).toLocalDateTime();
            } catch (Exception e) {
                at = LocalDateTime.now(CN);
            }
            LocalDateTime finalAt = at;

            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO transactions
                          (user_id, type, amount, category, merchant, note, occurred_at, source)
                        VALUES (?,?,?,?,?,?,?,?)
                        """, new String[]{"id"});
                ps.setLong(1, uid);
                ps.setString(2, it.type());
                ps.setLong(3, it.amount());
                ps.setString(4, it.category());
                ps.setString(5, it.merchant());
                ps.setString(6, it.note());
                ps.setObject(7, finalAt);
                ps.setString(8, src);
                return ps;
            }, kh);

            Number key = kh.getKey();
            if (key == null) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INSERT_FAILED");
            ids.add(key.longValue());
        }

        // model_output 保存模型原始返回（含模型名），final_record 保存用户确认后的结果，
        // 两者一起才能还原「模型说了什么、人改了什么」；手动记账没有文本输入，不写反馈表
        if (text != null && !text.isBlank()) {
            String modelOutput = null;
            if (raw != null && !raw.isBlank()) {
                modelOutput = writeJson(Map.of(
                        "model", model == null ? "" : model,
                        "raw", raw));
            }
            jdbc.update("""
                    INSERT INTO parse_feedback(user_id, input_text, model_output, final_record, edited)
                    VALUES (?,?,?,?,?)
                    """, uid, text, modelOutput, writeJson(items), edited ? 1 : 0);
        }

        return ids;
    }

    /**
     * 账单列表。from/to 优先（含端点），否则 month（整月），都没有就最近 500 条；
     * q 同时搜备注/商家/分类名，type 与 category 是可选的过滤条件。
     */
    public List<TxRow> list(long uid, String month, String from, String to,
                            String q, String type, String category, Integer limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.type, t.amount, t.category, t.merchant, t.note, t.occurred_at
                FROM transactions t LEFT JOIN categories c ON c.id = t.category
                WHERE t.user_id = ? AND t.deleted = 0
                """);
        List<Object> args = new ArrayList<>();
        args.add(uid);

        LocalDateTime start = null;
        LocalDateTime end = null;
        if (notBlank(from) || notBlank(to)) {
            LocalDate f = parseDay(from);
            LocalDate t = parseDay(to);
            if (f.isAfter(t)) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_RANGE");
            start = f.atStartOfDay();
            end = t.plusDays(1).atStartOfDay();
        } else if (notBlank(month)) {
            LocalDateTime ms = monthStart(month);
            start = ms;
            end = ms.plusMonths(1);
        }
        if (start != null) {
            sql.append(" AND t.occurred_at >= ? AND t.occurred_at < ?");
            args.add(start);
            args.add(end);
        }

        if (notBlank(type)) {
            if (!"expense".equals(type) && !"income".equals(type)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_TYPE");
            }
            sql.append(" AND t.type = ?");
            args.add(type);
        }
        if (notBlank(category)) {
            sql.append(" AND t.category = ?");
            args.add(category.trim());
        }
        if (notBlank(q)) {
            String like = "%" + escapeLike(q.trim()) + "%";
            sql.append(" AND (t.note LIKE ? OR t.merchant LIKE ? OR c.name LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }

        int n = limit == null ? 500 : Math.max(1, Math.min(2000, limit));
        sql.append(" ORDER BY t.occurred_at DESC, t.id DESC LIMIT ?");
        args.add(n);

        return jdbc.query(sql.toString(), (rs, i) -> map(rs), args.toArray());
    }

    /** 修改一笔账单；只传要改的字段，其余保留原值 */
    @Transactional
    public TxRow update(long uid, long id, TxDtos.EditReq req) {
        TxRow cur = find(uid, id);
        String type = notBlank(req.type()) ? req.type() : cur.type();
        if (!"expense".equals(type) && !"income".equals(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_TYPE");
        }
        long amount = req.amount() == null ? cur.amount() : req.amount();
        if (amount <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "AMOUNT_INVALID");

        String category = notBlank(req.category()) ? req.category().trim() : cur.category();
        if (category.length() > 32) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_CATEGORY");

        String merchant = trimTo(req.merchant(), cur.merchant(), 128);
        String note = trimTo(req.note(), cur.note(), 500);

        LocalDateTime at = req.occurredAt() == null || req.occurredAt().isBlank()
                ? parseStored(cur.occurredAt())
                : parseTime(req.occurredAt());

        jdbc.update("""
                UPDATE transactions SET type = ?, amount = ?, category = ?, merchant = ?,
                       note = ?, occurred_at = ?
                WHERE id = ? AND user_id = ? AND deleted = 0
                """, type, amount, category, merchant, note, at, id, uid);

        return find(uid, id);
    }

    public void softDelete(long uid, long id) {
        jdbc.update("UPDATE transactions SET deleted = 1 WHERE id = ? AND user_id = ?", id, uid);
    }

    public List<SummaryRow> summary(long uid, String month) {
        LocalDateTime start = monthStart(month);
        return jdbc.query("""
                SELECT type, category, SUM(amount) AS total, COUNT(*) AS n
                FROM transactions
                WHERE user_id = ? AND deleted = 0
                  AND occurred_at >= ? AND occurred_at < ?
                GROUP BY type, category
                """, (rs, i) -> new SummaryRow(
                        rs.getString("type"), rs.getString("category"),
                        rs.getLong("total"), rs.getLong("n")),
                uid, start, start.plusMonths(1));
    }

    /**
     * 导出 CSV：from/to 优先，其次 month（为空或 "all" 导出全部）；带 BOM，Excel 直接打开不乱码
     */
    public String exportCsv(long uid, String month, String from, String to) {
        String base = """
                SELECT t.occurred_at, t.type, t.amount, t.category,
                       COALESCE(c.name, t.category) AS cat_name, t.merchant, t.note, t.source
                FROM transactions t LEFT JOIN categories c ON c.id = t.category
                WHERE t.user_id = ? AND t.deleted = 0
                """;

        List<Map<String, Object>> rows;
        if (notBlank(from) || notBlank(to)) {
            LocalDate f = parseDay(from);
            LocalDate t = parseDay(to);
            if (f.isAfter(t)) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_RANGE");
            rows = jdbc.queryForList(base + " AND t.occurred_at >= ? AND t.occurred_at < ? ORDER BY t.occurred_at DESC",
                    uid, f.atStartOfDay(), t.plusDays(1).atStartOfDay());
        } else if (month == null || month.isBlank() || "all".equalsIgnoreCase(month)) {
            rows = jdbc.queryForList(base + " ORDER BY t.occurred_at DESC", uid);
        } else {
            LocalDateTime start = monthStart(month);
            rows = jdbc.queryForList(base + " AND t.occurred_at >= ? AND t.occurred_at < ? ORDER BY t.occurred_at DESC",
                    uid, start, start.plusMonths(1));
        }

        StringBuilder sb = new StringBuilder("\ufeff");
        sb.append("日期时间,类型,金额,分类,备注,商家,来源\r\n");
        for (Map<String, Object> r : rows) {
            LocalDateTime at = toLocal(r.get("occurred_at"));
            long amount = ((Number) r.get("amount")).longValue();
            String type = String.valueOf(r.get("type"));
            sb.append(csvCell(at == null ? "" : at.format(MINUTE))).append(',')
                    .append("income".equals(type) ? "收入" : "支出").append(',')
                    .append(BigDecimal.valueOf(amount, 2).toPlainString()).append(',')
                    .append(csvCell(r.get("cat_name"))).append(',')
                    .append(csvCell(r.get("note"))).append(',')
                    .append(csvCell(r.get("merchant"))).append(',')
                    .append("manual".equals(String.valueOf(r.get("source"))) ? "手动" : "AI")
                    .append("\r\n");
        }
        return sb.toString();
    }

    private TxRow find(long uid, long id) {
        List<TxRow> rows = jdbc.query("""
                SELECT id, type, amount, category, merchant, note, occurred_at
                FROM transactions WHERE id = ? AND user_id = ? AND deleted = 0
                """, (rs, i) -> map(rs), id, uid);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND");
        return rows.get(0);
    }

    private static String trimTo(String v, String cur, int max) {
        String s = v == null ? cur : v.trim();
        if (s != null && s.isEmpty()) s = null;
        if (s != null && s.length() > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEXT_TOO_LONG");
        }
        return s;
    }

    private static LocalDateTime parseTime(String s) {
        String v = s.trim();
        try {
            return OffsetDateTime.parse(v).atZoneSameInstant(CN).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 再试本地时间
        }
        try {
            return LocalDateTime.parse(v);
        } catch (DateTimeParseException ignored) {
            // 再试「yyyy-MM-dd HH:mm」
        }
        try {
            return LocalDateTime.parse(v, MINUTE);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_TIME");
        }
    }

    private static LocalDateTime parseStored(String iso) {
        try {
            return OffsetDateTime.parse(iso).atZoneSameInstant(CN).toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.now(CN);
        }
    }

    private static LocalDate parseDay(String s) {
        if (!notBlank(s)) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_RANGE");
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_RANGE");
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static LocalDateTime toLocal(Object v) {
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
    }

    private static String csvCell(Object v) {
        String s = v == null ? "" : String.valueOf(v).replace("\r", " ").replace("\n", " ");
        if (s.contains(",") || s.contains("\"")) s = '"' + s.replace("\"", "\"\"") + '"';
        return s;
    }

    private LocalDateTime monthStart(String month) {
        if (month == null || month.isBlank()) {
            return LocalDate.now(CN).withDayOfMonth(1).atStartOfDay();
        }
        try {
            return YearMonth.parse(month).atDay(1).atStartOfDay();
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_MONTH");
        }
    }

    private TxRow map(ResultSet rs) throws SQLException {
        LocalDateTime at = rs.getObject("occurred_at", LocalDateTime.class);
        return new TxRow(rs.getLong("id"), rs.getString("type"), rs.getLong("amount"),
                rs.getString("category"), rs.getString("merchant"), rs.getString("note"),
                at.atZone(CN).toOffsetDateTime().toString());
    }

    private String writeJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
