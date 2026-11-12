package com.surf.ledger.wx;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surf.ledger.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class WxService {
    private final RestClient http;
    private final ObjectMapper json;

    @Value("${wx.appid}") private String appid;
    @Value("${wx.secret}") private String secret;

    public WxService(RestClient.Builder builder, ObjectMapper json) {
        this.http = builder.baseUrl("https://api.weixin.qq.com").build();
        this.json = json;
    }

    public String openid(String code) {
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WX_NOT_CONFIGURED",
                    "后台还没配置微信登录：在 server/.env 里加 WX_APPID 和 WX_SECRET（微信公众平台 → 开发管理 → 开发设置），改完重启后端");
        }

        /* 微信这个接口返回的是 Content-Type: text/plain，不能直接映射成对象，
           否则会被 Spring 当成不支持的媒体类型抛异常（以前线上 500 INTERNAL 的原因）。 */
        String body;
        try {
            body = http.get()
                    .uri(u -> u.path("/sns/jscode2session")
                            .queryParam("appid", appid)
                            .queryParam("secret", secret)
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "WX_UPSTREAM", "连不上微信服务器：" + e.getMessage());
        }

        if (body == null || body.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "WX_UPSTREAM", "微信服务器返回了空响应");
        }

        WxSession s;
        try {
            s = json.readValue(body, WxSession.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "WX_UPSTREAM",
                    "微信服务器返回了无法解析的内容：" + snippet(body));
        }

        if (s == null || s.errcode() != null || s.openid() == null) {
            String msg = s == null ? "空响应" : (s.errcode() + " " + s.errmsg());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "WX_LOGIN_FAILED",
                    "微信登录失败（" + msg + "）。常见原因：小程序 appid 与后台 WX_APPID 不一致，或 AppSecret 填错");
        }
        return s.openid();
    }

    private static String snippet(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 120 ? t : t.substring(0, 120) + "…";
    }

    public record WxSession(String openid,
                            @JsonProperty("session_key") String sessionKey,
                            Integer errcode,
                            String errmsg) {}
}
