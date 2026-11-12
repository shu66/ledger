package com.surf.ledger.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.surf.ledger.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LlmClient {
    private final RestClient.Builder builder;
    private final int connectTimeout;
    private final int readTimeout;

    public LlmClient(RestClient.Builder builder,
                     @Value("${llm.connect-timeout-ms}") int connectTimeout,
                     @Value("${llm.read-timeout-ms}") int readTimeout) {
        this.builder = builder;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    /** JSON 模式：用于解析，保证结构化输出 */
    public String chat(LlmConfigService.LlmConfig cfg, String system, String user) {
        return chat(cfg, system, user, true);
    }

    /** jsonMode=false 时不发 response_format，模型可以自由回文本（管理后台连通性测试用） */
    public String chat(LlmConfigService.LlmConfig cfg, String system, String user, boolean jsonMode) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);

        RestClient http = builder.clone()
                .baseUrl(cfg.baseUrl())
                .defaultHeader("Authorization", "Bearer " + cfg.apiKey())
                .requestFactory(factory)
                .build();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("temperature", 0);
        if (jsonMode) body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)
        ));

        JsonNode res;
        try {
            res = http.post().uri("/chat/completions").body(body)
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            String upstream = e.getResponseBodyAsString();
            if (upstream != null && upstream.length() > 300) upstream = upstream.substring(0, 300);
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "LLM_UPSTREAM_" + e.getStatusCode().value(), upstream);
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "LLM_TIMEOUT", e.getMessage());
        }

        String content = res == null ? null
                : res.path("choices").path(0).path("message").path("content").asText(null);

        if (content == null || content.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EMPTY_MODEL_OUTPUT");
        }
        return content.replaceAll("(?s)^```(?:json)?", "").replaceAll("(?s)```$", "").trim();
    }
}
