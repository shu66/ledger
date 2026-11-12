package com.surf.ledger.ai;

import com.surf.ledger.common.ApiException;
import com.surf.ledger.tx.ReportService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * AI 总结：把区间统计压成一段提示词，让模型给三五句人话分析。
 * 只喂聚合数据，不喂逐笔明细，省钱也不泄露过多细节。
 */
@Service
public class SummaryService {
    private static final String SYS = """
            你是记账应用里的财务分析助手。只根据给定的统计数字说话，不要编造数字，不要输出 Markdown 标题。
            用中文写 3 到 5 句：先说这个区间整体花得怎么样，再点出一两个结构上的特点，
            最后给一句具体、可执行的建议。语气平实，像一个懂账的朋友，不要说教。
            """;

    private final ReportService reports;
    private final LlmConfigService configs;
    private final LlmClient llm;

    public SummaryService(ReportService reports, LlmConfigService configs, LlmClient llm) {
        this.reports = reports;
        this.configs = configs;
        this.llm = llm;
    }

    public record SummaryRes(String text, String model, String from, String to, long expense,
                             long income, int count) {}

    public SummaryRes summarize(long uid, String month, String from, String to) {
        Map<String, Object> rep = reports.report(uid, month, from, to);
        int count = ((Number) rep.get("count")).intValue();
        if (count == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_DATA", "这个区间还没有账目，先记两笔再来分析");
        }

        String prompt = buildPrompt(rep);
        ApiException last = null;
        for (LlmConfigService.LlmConfig cfg : configs.candidates()) {
            try {
                String text = llm.chat(cfg, SYS, prompt, false).trim();
                if (text.isEmpty()) continue;
                return new SummaryRes(text, cfg.model(), (String) rep.get("from"), (String) rep.get("to"),
                        ((Number) rep.get("expense")).longValue(),
                        ((Number) rep.get("income")).longValue(), count);
            } catch (ApiException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED",
                "还没有可用的 AI 模型，请管理员在后台添加并启用");
    }

    @SuppressWarnings("unchecked")
    private String buildPrompt(Map<String, Object> rep) {
        long expense = ((Number) rep.get("expense")).longValue();
        long income = ((Number) rep.get("income")).longValue();
        long prevExpense = ((Number) rep.get("prevExpense")).longValue();
        long prevIncome = ((Number) rep.get("prevIncome")).longValue();

        StringBuilder sb = new StringBuilder();
        sb.append("统计区间：").append(rep.get("from")).append(" 至 ").append(rep.get("to"))
                .append("（").append(rep.get("days")).append(" 天，").append(rep.get("count")).append(" 笔）\n");
        sb.append("支出 ").append(yuan(expense)).append(" 元，收入 ").append(yuan(income)).append(" 元，");
        sb.append("结余 ").append(yuan(income - expense)).append(" 元\n");
        sb.append("上一段等长区间（").append(rep.get("prevFrom")).append(" 至 ").append(rep.get("prevTo"))
                .append("）：支出 ").append(yuan(prevExpense)).append(" 元，收入 ")
                .append(yuan(prevIncome)).append(" 元\n");

        List<Map<String, Object>> cats = (List<Map<String, Object>>) rep.get("byCategory");
        StringBuilder out = new StringBuilder();
        StringBuilder in = new StringBuilder();
        int shown = 0;
        for (Map<String, Object> c : cats) {
            long total = ((Number) c.get("total")).longValue();
            String line = "  - " + c.get("category") + "：" + yuan(total) + " 元（"
                    + ((Number) c.get("n")).intValue() + " 笔）\n";
            if ("income".equals(c.get("type"))) {
                if (in.length() < 400) in.append(line);
            } else if (shown++ < 6) {
                out.append(line);
            }
        }
        if (out.length() > 0) sb.append("支出分类：\n").append(out);
        if (in.length() > 0) sb.append("收入分类：\n").append(in);

        List<Map<String, Object>> days = (List<Map<String, Object>>) rep.get("byDay");
        Map<String, Object> peak = null;
        for (Map<String, Object> d : days) {
            long e = ((Number) d.get("expense")).longValue();
            if (peak == null || e > ((Number) peak.get("expense")).longValue()) peak = d;
        }
        if (peak != null) {
            sb.append("单日支出最高：").append(peak.get("day")).append("，")
                    .append(yuan(((Number) peak.get("expense")).longValue())).append(" 元\n");
        }

        List<Map<String, Object>> merchants = (List<Map<String, Object>>) rep.get("topMerchants");
        if (!merchants.isEmpty()) {
            sb.append("支出最多的商家/说明：");
            for (int i = 0; i < Math.min(5, merchants.size()); i++) {
                Map<String, Object> m = merchants.get(i);
                if (i > 0) sb.append("、");
                sb.append(m.get("name")).append(" ").append(yuan(((Number) m.get("total")).longValue())).append(" 元");
            }
            sb.append('\n');
        }

        sb.append("\n请按「整体情况 → 结构特点 → 一句建议」来写，不要逐条复述数字。");
        return sb.toString();
    }

    private static String yuan(long fen) {
        return BigDecimal.valueOf(fen, 2).toPlainString();
    }
}
