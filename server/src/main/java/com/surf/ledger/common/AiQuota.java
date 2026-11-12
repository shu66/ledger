package com.surf.ledger.common;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按「用户 + 自然日 + 类型」计数的 AI 配额。库里累加，重启不清零；
 * 超过上限抛 429，前端提示今天次数用完。
 */
@Service
public class AiQuota {
    public static final String PARSE = "parse";
    public static final String SUMMARY = "summary";

    private final JdbcTemplate jdbc;

    public AiQuota(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 先用一次，再判断是否超限；返回值是今天已经用掉的次数（含本次） */
    public int consume(long uid, String kind, int limit) {
        jdbc.update("""
                INSERT INTO ai_usage(user_id, day, kind, n) VALUES (?, CURDATE(), ?, 1)
                ON DUPLICATE KEY UPDATE n = n + 1
                """, uid, kind);
        Integer n = jdbc.queryForObject(
                "SELECT n FROM ai_usage WHERE user_id = ? AND day = CURDATE() AND kind = ?",
                Integer.class, uid, kind);
        int used = n == null ? 1 : n;
        if (used > limit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_QUOTA_EXCEEDED",
                    "今天的 AI " + ("parse".equals(kind) ? "解析" : "总结")
                            + "次数已用完（每天 " + limit + " 次），明天再来");
        }
        return used;
    }

    public Map<String, Object> usage(long uid, int parseLimit, int summaryLimit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("parseUsed", used(uid, PARSE));
        out.put("parseLimit", parseLimit);
        out.put("summaryUsed", used(uid, SUMMARY));
        out.put("summaryLimit", summaryLimit);
        return out;
    }

    private int used(long uid, String kind) {
        Integer n = jdbc.queryForObject("""
                SELECT COALESCE((SELECT n FROM ai_usage WHERE user_id = ? AND day = CURDATE() AND kind = ?), 0)
                """, Integer.class, uid, kind);
        return n == null ? 0 : n;
    }
}
