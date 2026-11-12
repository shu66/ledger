package com.surf.ledger.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimiter {
    private static final int SWEEP_THRESHOLD = 4096;

    private record Bucket(long start, int count) {}

    private final ConcurrentHashMap<String, Bucket> map = new ConcurrentHashMap<>();
    private final int limit;
    private final long windowMs;

    public RateLimiter(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
    }

    public synchronized boolean allow(String key) {
        long now = System.currentTimeMillis();
        if (map.size() > SWEEP_THRESHOLD) sweep(now);

        Bucket b = map.get(key);
        if (b == null || now - b.start() > windowMs) {
            map.put(key, new Bucket(now, 1));
            return true;
        }
        if (b.count() >= limit) return false;
        map.put(key, new Bucket(b.start(), b.count() + 1));
        return true;
    }

    /** 清掉过期的桶，避免被扫 IP 时 Map 无限增长 */
    private void sweep(long now) {
        for (Map.Entry<String, Bucket> e : map.entrySet()) {
            if (now - e.getValue().start() > windowMs) map.remove(e.getKey(), e.getValue());
        }
    }
}
