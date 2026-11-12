package com.surf.ledger.ai;

import java.util.List;

public final class ParseDtos {
    private ParseDtos() {}

    public record RawResp(List<RawItem> items) {}

    public record RawItem(String type, Integer amount, String category,
                          String merchant, String note, String occurredAt, String suggest) {}

    public record Flags(boolean inText, boolean derived) {}

    public record Item(String type, Integer amount, String category, String merchant,
                       String note, String occurredAt, String suggest,
                       double confidence, Flags flags) {}

    public record ParseResponse(List<Item> items, String raw, String model,
                                List<String> createdCategories) {}
}
