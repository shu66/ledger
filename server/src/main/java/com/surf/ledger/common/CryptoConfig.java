package com.surf.ledger.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CryptoConfig {
    @Bean
    public Crypto crypto(@Value("${app.secret:}") String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "APP_SECRET 没配置或太短（至少 32 字符）。"
                            + "在 server/.env 里写 APP_SECRET=...（openssl rand -hex 32），"
                            + "或设置同名环境变量。");
        }
        return new Crypto(secret);
    }
}
