package com.surf.ledger.wx;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.surf.ledger.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class WxService {
    private final RestClient http;

    @Value("${wx.appid}") private String appid;
    @Value("${wx.secret}") private String secret;

    public WxService(RestClient.Builder builder) {
        this.http = builder.baseUrl("https://api.weixin.qq.com").build();
    }

    public String openid(String code) {
        WxSession s = http.get()
                .uri(u -> u.path("/sns/jscode2session")
                        .queryParam("appid", appid)
                        .queryParam("secret", secret)
                        .queryParam("js_code", code)
                        .queryParam("grant_type", "authorization_code")
                        .build())
                .retrieve()
                .body(WxSession.class);

        if (s == null || s.errcode() != null || s.openid() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "WX_LOGIN_FAILED");
        }
        return s.openid();
    }

    public record WxSession(String openid,
                            @JsonProperty("session_key") String sessionKey,
                            Integer errcode,
                            String errmsg) {}
}
