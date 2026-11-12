package com.surf.ledger.ai;

import com.surf.ledger.auth.AuthInterceptor;
import com.surf.ledger.common.AiQuota;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final SummaryService summaries;
    private final AiQuota quota;

    @Value("${app.ai-parse-daily:50}")
    private int parseDaily;
    @Value("${app.ai-summary-daily:20}")
    private int summaryDaily;

    public AiController(SummaryService summaries, AiQuota quota) {
        this.summaries = summaries;
        this.quota = quota;
    }

    /** POST /api/ai/summary {from,to} 或 {month}：生成区间账单分析 */
    @PostMapping("/summary")
    public Map<String, Object> summary(@RequestAttribute(AuthInterceptor.UID) long uid,
                                       @RequestBody(required = false) Map<String, String> body) {
        String month = body == null ? null : blankToNull(body.get("month"));
        String from = body == null ? null : blankToNull(body.get("from"));
        String to = body == null ? null : blankToNull(body.get("to"));

        quota.consume(uid, AiQuota.SUMMARY, summaryDaily);
        SummaryService.SummaryRes res = summaries.summarize(uid, month, from, to);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", res.text());
        out.put("model", res.model());
        out.put("from", res.from());
        out.put("to", res.to());
        out.put("expense", res.expense());
        out.put("income", res.income());
        out.put("count", res.count());
        out.put("summaryUsed", quota.usage(uid, parseDaily, summaryDaily).get("summaryUsed"));
        out.put("summaryLimit", summaryDaily);
        return out;
    }

    /** GET /api/ai/usage：今天的解析/总结已用次数，前端的「剩几次」提示用 */
    @GetMapping("/usage")
    public Map<String, Object> usage(@RequestAttribute(AuthInterceptor.UID) long uid) {
        return quota.usage(uid, parseDaily, summaryDaily);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
