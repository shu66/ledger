package com.surf.ledger.ai;

import com.surf.ledger.ai.ParseDtos.ParseResponse;
import com.surf.ledger.auth.AuthInterceptor;
import com.surf.ledger.common.AiQuota;
import com.surf.ledger.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ParseController {
    private static final int MAX_TEXT = 500;

    private final ParseService service;
    private final AiQuota quota;

    @Value("${app.ai-parse-daily:50}")
    private int parseDaily;

    public ParseController(ParseService service, AiQuota quota) {
        this.service = service;
        this.quota = quota;
    }

    @PostMapping("/parse")
    public ParseResponse parse(@RequestAttribute(AuthInterceptor.UID) long uid,
                               @RequestBody(required = false) Map<String, String> body) {
        String text = body == null ? null : body.get("text");
        if (text == null || text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_TEXT");
        }
        text = text.trim();
        if (text.length() > MAX_TEXT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEXT_TOO_LONG");
        }
        quota.consume(uid, AiQuota.PARSE, parseDaily);
        return service.parse(uid, text);
    }
}
