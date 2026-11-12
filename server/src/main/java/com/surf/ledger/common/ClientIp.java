package com.surf.ledger.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 取客户端 IP：只有显式信任反代（app.trust-proxy=true）时才读 X-Forwarded-For */
@Component
public class ClientIp {
    private final boolean trustProxy;

    public ClientIp(@Value("${app.trust-proxy:false}") boolean trustProxy) {
        this.trustProxy = trustProxy;
    }

    public String of(HttpServletRequest req) {
        if (trustProxy) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return trim(comma > 0 ? xff.substring(0, comma) : xff);
            }
            String real = req.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) return trim(real);
        }
        return trim(req.getRemoteAddr());
    }

    private String trim(String ip) {
        String s = ip == null ? "" : ip.trim();
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
