package com.surf.ledger.tx;

import com.surf.ledger.auth.AuthInterceptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ReportController {
    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    /**
     * GET /api/report?month=2026-11 或者 ?from=2026-11-01&to=2026-11-30
     * 缺省当月；一次返回图表需要的全部聚合。
     */
    @GetMapping("/report")
    public Map<String, Object> report(@RequestAttribute(AuthInterceptor.UID) long uid,
                                      @RequestParam(required = false) String month,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        return service.report(uid, month, from, to);
    }
}
