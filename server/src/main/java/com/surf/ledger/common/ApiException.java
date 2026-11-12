package com.surf.ledger.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String detail;

    public ApiException(HttpStatus status, String code) {
        this(status, code, null);
    }

    /** detail 会作为 message 字段回给前端，用于展示上游服务返回的错误 */
    public ApiException(HttpStatus status, String code, String detail) {
        super(code);
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String detail() { return detail; }
}
