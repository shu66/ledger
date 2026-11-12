package com.surf.ledger.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpTest {

    @Test
    void ignoresForwardedHeadersWhenProxyNotTrusted() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.5");
        req.addHeader("X-Forwarded-For", "1.2.3.4");
        req.addHeader("X-Real-IP", "5.6.7.8");
        assertEquals("10.0.0.5", new ClientIp(false).of(req));
    }

    @Test
    void takesFirstForwardedHopWhenTrusted() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.1");
        assertEquals("1.2.3.4", new ClientIp(true).of(req));
    }

    @Test
    void fallsBackToRealIpHeader() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Real-IP", "5.6.7.8");
        assertEquals("5.6.7.8", new ClientIp(true).of(req));
    }

    @Test
    void truncatesTo64Chars() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("x".repeat(100));
        assertEquals(64, new ClientIp(false).of(req).length());
    }
}
