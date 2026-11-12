package com.surf.ledger.tx;

import com.surf.ledger.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账单统计：一次请求给前端图表全部数据（区间汇总、分类占比、每日走势、趋势、商家排行）。
 * 区间由 from/to（含端点）决定；不传时退回 month（整月）。
 */
@Service
public class ReportService {
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final int MAX_DAYS = 731;

    private final JdbcTemplate jdbc;

    public ReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> report(long uid, String month, String from, String to) {
        Range r = range(month, from, to);
        long days = ChronoUnit.DAYS.between(r.from(), r.to()) + 1;

        LocalDate prevTo = r.from().minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1);

        Totals cur = totals(uid, r.from(), r.to());
        Totals pre = totals(uid, prevFrom, prevTo);

        LocalDateTime start = r.from().atStartOfDay();
        LocalDateTime end = r.to().plusDays(1).atStartOfDay();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", r.from().toString());
        out.put("to", r.to().toString());
        out.put("days", days);
        if (r.month() != null) out.put("month", r.month());
        out.put("expense", cur.expense());
        out.put("income", cur.income());
        out.put("count", cur.count());
        out.put("prevFrom", prevFrom.toString());
        out.put("prevTo", prevTo.toString());
        out.put("prevExpense", pre.expense());
        out.put("prevIncome", pre.income());
        out.put("prevCount", pre.count());
        out.put("byCategory", byCategory(uid, start, end));
        out.put("byDay", byDay(uid, start, end));
        out.put("trend", trend(uid, r.from(), r.to(), days));
        out.put("topMerchants", topMerchants(uid, start, end));
        return out;
    }

    /** 某月整月的 report（给旧接口/AI 总结复用） */
    public Map<String, Object> report(long uid, String month) {
        return report(uid, month, null, null);
    }

    private record Range(LocalDate from, LocalDate to, String month) {}

    private Range range(String month, String from, String to) {
        boolean hasFrom = from != null && !from.isBlank();
        boolean hasTo = to != null && !to.isBlank();
        if (hasFrom || hasTo) {
            if (!hasFrom || !hasTo) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_RANGE");
            LocalDate f = parseDate(from);
            LocalDate t = parseDate(to);
            if (f.isAfter(t)) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_RANGE");
            if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_DAYS) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "RANGE_TOO_LONG");
            }
            return new Range(f, t, null);
        }
        YearMonth ym = monthOf(month);
        return new Range(ym.atDay(1), ym.atEndOfMonth(), ym.toString());
    }

    private LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_RANGE");
        }
    }

    private YearMonth monthOf(String month) {
        if (month == null || month.isBlank()) return YearMonth.now(CN);
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_MONTH");
        }
    }

    private record Totals(long expense, long income, int count) {}

    private Totals totals(long uid, LocalDate from, LocalDate to) {
        return jdbc.query(
                "SELECT type, COALESCE(SUM(amount), 0) t, COUNT(*) n FROM transactions " +
                        "WHERE user_id = ? AND deleted = 0 AND occurred_at >= ? AND occurred_at < ? GROUP BY type",
                rs -> {
                    long expense = 0, income = 0;
                    int count = 0;
                    while (rs.next()) {
                        String type = rs.getString("type");
                        long t = rs.getLong("t");
                        int n = rs.getInt("n");
                        count += n;
                        if ("income".equals(type)) income += t;
                        else expense += t;
                    }
                    return new Totals(expense, income, count);
                }, uid, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    private Totals totals(long uid, YearMonth ym) {
        return totals(uid, ym.atDay(1), ym.atEndOfMonth());
    }

    private List<Map<String, Object>> byCategory(long uid, LocalDateTime start, LocalDateTime end) {
        return jdbc.query(
                "SELECT category, type, SUM(amount) total, COUNT(*) n FROM transactions " +
                        "WHERE user_id = ? AND deleted = 0 AND occurred_at >= ? AND occurred_at < ? " +
                        "GROUP BY category, type ORDER BY total DESC",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("category", rs.getString("category"));
                    row.put("type", rs.getString("type"));
                    row.put("total", rs.getLong("total"));
                    row.put("n", rs.getInt("n"));
                    return row;
                }, uid, start, end);
    }

    private List<Map<String, Object>> byDay(long uid, LocalDateTime start, LocalDateTime end) {
        return jdbc.query(
                "SELECT DATE(occurred_at) d, " +
                        "SUM(CASE WHEN type = 'expense' THEN amount ELSE 0 END) e, " +
                        "SUM(CASE WHEN type = 'income' THEN amount ELSE 0 END) i " +
                        "FROM transactions WHERE user_id = ? AND deleted = 0 " +
                        "AND occurred_at >= ? AND occurred_at < ? GROUP BY DATE(occurred_at) ORDER BY d",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("day", rs.getString("d"));
                    row.put("expense", rs.getLong("e"));
                    row.put("income", rs.getLong("i"));
                    return row;
                }, uid, start, end);
    }

    /**
     * 图表趋势：区间短于 63 天给「最近 6 个月」，更长给区间内的逐月（最多 13 个月），
     * 这样按年看得到 1–12 月，按日看也有月度背景。
     */
    private List<Map<String, Object>> trend(long uid, LocalDate from, LocalDate to, long days) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (days <= 62) {
            YearMonth end = YearMonth.from(to);
            for (int i = 5; i >= 0; i--) rows.add(trendRow(uid, end.minusMonths(i)));
        } else {
            YearMonth m = YearMonth.from(from);
            YearMonth last = YearMonth.from(to);
            while (!m.isAfter(last) && rows.size() < 13) {
                rows.add(trendRow(uid, m));
                m = m.plusMonths(1);
            }
        }
        return rows;
    }

    private Map<String, Object> trendRow(long uid, YearMonth m) {
        Totals t = totals(uid, m);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("month", m.toString());
        row.put("expense", t.expense());
        row.put("income", t.income());
        return row;
    }

    private List<Map<String, Object>> topMerchants(long uid, LocalDateTime start, LocalDateTime end) {
        return jdbc.query(
                "SELECT COALESCE(NULLIF(merchant, ''), NULLIF(note, ''), category) name, " +
                        "SUM(amount) total, COUNT(*) n FROM transactions " +
                        "WHERE user_id = ? AND deleted = 0 AND type = 'expense' " +
                        "AND occurred_at >= ? AND occurred_at < ? " +
                        "GROUP BY name ORDER BY total DESC LIMIT 6",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", rs.getString("name"));
                    row.put("total", rs.getLong("total"));
                    row.put("n", rs.getInt("n"));
                    return row;
                }, uid, start, end);
    }

    /** 供别处复用：把 "2026-11" 转成月初（缺省当月） */
    public static LocalDate firstDay(String month) {
        if (month == null || month.isBlank()) return LocalDate.now(CN).withDayOfMonth(1);
        try {
            return YearMonth.parse(month.trim()).atDay(1);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_MONTH");
        }
    }
}
