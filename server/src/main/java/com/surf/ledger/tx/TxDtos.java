package com.surf.ledger.tx;

import com.surf.ledger.ai.ParseDtos.Item;

import java.util.List;

public final class TxDtos {
    private TxDtos() {}

    /** raw/model 是 /api/parse 返回的模型原始输出，仅用于把反馈写进 parse_feedback；
     *  source 是 text（AI 解析）或 manual（手动记账） */
    public record BatchReq(List<Item> items, String text, String raw, String model,
                           Boolean edited, String source) {}
    public record BatchRes(List<Long> ids) {}
    public record TxRow(long id, String type, long amount, String category,
                        String merchant, String note, String occurredAt) {}
    public record SummaryRow(String type, String category, long total, long n) {}

    /** 改一笔账单：字段为 null 表示不动，amount 单位是分，occurredAt 接受 ISO 或 yyyy-MM-dd HH:mm */
    public record EditReq(String type, Long amount, String category, String merchant,
                          String note, String occurredAt) {}
}
