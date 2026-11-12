package com.surf.ledger.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void allowsUpToLimitThenBlocks() {
        RateLimiter rl = new RateLimiter(3, 60_000);
        assertTrue(rl.allow("a"));
        assertTrue(rl.allow("a"));
        assertTrue(rl.allow("a"));
        assertFalse(rl.allow("a"));
        // 不同 key 互不影响
        assertTrue(rl.allow("b"));
    }

    @Test
    void windowExpiryGivesFreshQuota() throws Exception {
        RateLimiter rl = new RateLimiter(1, 50);
        assertTrue(rl.allow("a"));
        assertFalse(rl.allow("a"));
        Thread.sleep(80);
        assertTrue(rl.allow("a"));
    }
}
