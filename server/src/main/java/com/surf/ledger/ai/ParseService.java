package com.surf.ledger.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surf.ledger.ai.ParseDtos.Flags;
import com.surf.ledger.ai.ParseDtos.Item;
import com.surf.ledger.ai.ParseDtos.ParseResponse;
import com.surf.ledger.ai.ParseDtos.RawItem;
import com.surf.ledger.ai.ParseDtos.RawResp;
import com.surf.ledger.category.CategoryService;
import com.surf.ledger.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ParseService {
    private static final Logger log = LoggerFactory.getLogger(ParseService.class);

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final Set<String> ALLOWED = Set.of(
            "food", "transport", "shopping", "housing", "fun", "health", "social", "other_exp",
            "salary", "bonus_red", "refund", "reimburse", "other_inc");

    private static final Pattern NUM = Pattern.compile("\\d+(?:\\.\\d{1,2})?");
    private static final Pattern RISKY = Pattern.compile("AA|垫|借|还|折|代付");

    private static final String SYSTEM = """
            你是记账解析器。用户会用完全自由的中文描述记账：可能没有标点、没有格式、
            有错别字、省略主语。理解意图，输出结构化记录。

            【分类体系】只能从下面的 id 里选，禁止编造新 id：
            expense: food 餐饮(外卖/堂食/咖啡/奶茶/买菜/食堂)
                     transport 交通(打车/地铁/公交/加油/停车/机票高铁)
                     shopping 购物(淘宝/京东/日用品/衣服/数码)
                     housing 居住(房租/物业/水电燃气/家政)
                     fun 娱乐(电影/游戏/演出/会员订阅)
                     health 医疗(挂号/买药/体检)
                     social 人情(红包/请客/礼物/随礼)
                     other_exp 其他支出
            income:  salary 工资 / bonus_red 红包收入 / refund 退款
                     reimburse 报销 / other_inc 其他收入

            【规则】
            1. amount 单位是分，整数：3.1 元 -> 310。原文没有明确金额且无法合理推算时，
               amount 填 null，不要猜。
            2. 方向由语义决定，不是关键词。"卖红包收入3" 是 income；"给老王转45" 没说明
               用途时默认 expense。
            3. 一句话可能有多笔，输出多条。
            4. 相对时间（昨天/上周五/上个月）按 now 换算成 ISO8601；未提及用 now。
            5. 完全不含记账意图输出 {"items":[]}。
            6. 分类体系覆盖不了时用 other_exp / other_inc，并在 suggest 给一个中文新分类名。
            7. 只输出 JSON 对象，格式 {"items":[ ... ]}，不要解释。

            【now】%s

            【示例】
            输入：淘宝超红消费3.1
            输出：{"items":[{"type":"expense","amount":310,"category":"shopping","merchant":"淘宝","note":"超红","occurredAt":"%s","suggest":null}]}

            输入：卖淘宝红包收入3
            输出：{"items":[{"type":"income","amount":300,"category":"bonus_red","merchant":"淘宝","note":"卖红包","occurredAt":"%s","suggest":null}]}

            输入：中午跟老王吃饭他垫的我转他45
            输出：{"items":[{"type":"expense","amount":4500,"category":"food","merchant":null,"note":"跟老王吃饭，转他","occurredAt":"%s","suggest":null}]}

            输入：火锅240两个人AA
            输出：{"items":[{"type":"expense","amount":12000,"category":"food","merchant":null,"note":"火锅AA","occurredAt":"%s","suggest":null}]}

            输入：昨天打车回家28块5
            输出：{"items":[{"type":"expense","amount":2850,"category":"transport","merchant":"打车","note":"回家","occurredAt":"%s","suggest":null}]}

            输入：给猫买粮89
            输出：{"items":[{"type":"expense","amount":8900,"category":"other_exp","merchant":null,"note":"猫粮","occurredAt":"%s","suggest":"宠物"}]}
            """;

    private final LlmClient llm;
    private final LlmConfigService configs;
    private final ObjectMapper om;
    private final CategoryService categories;

    public ParseService(LlmClient llm, LlmConfigService configs, ObjectMapper om,
                        CategoryService categories) {
        this.llm = llm;
        this.configs = configs;
        this.om = om;
        this.categories = categories;
    }

    public ParseResponse parse(long uid, String text) {
        OffsetDateTime nowAt = OffsetDateTime.now(CN).truncatedTo(ChronoUnit.SECONDS);
        String now = nowAt.format(ISO);
        String yesterday = nowAt.minusDays(1).format(ISO);
        String system = SYSTEM.formatted(now, now, now, now, now, yesterday, now);

        List<LlmConfigService.LlmConfig> pool = configs.candidates();
        if (pool.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_LLM_CONFIG");
        }

        String raw = null;
        String usedModel = null;

        for (int i = 0; i < Math.min(2, pool.size()); i++) {
            try {
                raw = llm.chat(pool.get(i), system, text);
                usedModel = pool.get(i).model();
                break;
            } catch (Exception e) {
                log.warn("llm config {} failed: {}", pool.get(i).name(), e.toString());
            }
        }
        if (raw == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_FAILED");
        }

        RawResp parsed;
        try {
            parsed = om.readValue(raw, RawResp.class);
        } catch (Exception e) {
            log.warn("bad json from model: {}", raw);
            return new ParseResponse(List.of(), raw, usedModel, List.of());
        }

        List<String> nums = new ArrayList<>();
        Matcher m = NUM.matcher(text);
        while (m.find()) nums.add(m.group());

        List<Item> items = new ArrayList<>();
        List<String> created = new ArrayList<>();
        if (parsed != null && parsed.items() != null) {
            for (RawItem r : parsed.items()) {
                Item it = toItem(r, text, nums, now);
                // AI 建议的新分类直接落库（挂在当前用户名下），给模型更多自主权
                if (r.suggest() != null && !r.suggest().isBlank()
                        && ("other_exp".equals(it.category()) || "other_inc".equals(it.category()))) {
                    String newId = categories.ensureQuietly(uid, r.suggest(), it.type());
                    if (newId != null) {
                        it = new Item(it.type(), it.amount(), newId, it.merchant(), it.note(),
                                it.occurredAt(), r.suggest(), it.confidence(), it.flags());
                        created.add(r.suggest().trim());
                    }
                }
                items.add(it);
            }
        }
        return new ParseResponse(items, raw, usedModel, created);
    }

    private Item toItem(RawItem r, String text, List<String> nums, String now) {
        String type = "income".equals(r.type()) ? "income" : "expense";
        Integer amount = r.amount();
        String category = ALLOWED.contains(r.category())
                ? r.category()
                : ("income".equals(type) ? "other_inc" : "other_exp");

        boolean inText = false;
        boolean derived = false;
        if (amount != null && amount > 0) {
            double yuan = amount / 100.0;
            for (String n : nums) {
                double v = Double.parseDouble(n);
                if (Math.abs(v - yuan) < 0.005) { inText = true; break; }
                if (Math.abs(v / 2 - yuan) < 0.005 || Math.abs(v / 3 - yuan) < 0.005) derived = true;
            }
            if (inText) derived = false;
        }

        String occurredAt = now;
        if (r.occurredAt() != null && !r.occurredAt().isBlank()) {
            try {
                occurredAt = OffsetDateTime.parse(r.occurredAt()).format(ISO);
            } catch (Exception ignored) {
            }
        }

        return new Item(type, amount, category, r.merchant(), r.note(), occurredAt,
                r.suggest(), score(amount, inText, derived, text, category),
                new Flags(inText, derived));
    }

    private double score(Integer amount, boolean inText, boolean derived, String text, String category) {
        double s = 0.2;
        if (inText) s += 0.45;
        else if (derived) s += 0.15;
        else if (amount != null) s -= 0.25;

        if (amount == null) s -= 0.5;
        if (RISKY.matcher(text).find()) s -= 0.2;
        if (!ALLOWED.contains(category)) s -= 0.3;

        s = Math.max(0, Math.min(1, s));
        return Math.round(s * 100) / 100.0;
    }
}
