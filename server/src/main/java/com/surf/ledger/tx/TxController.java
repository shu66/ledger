package com.surf.ledger.tx;

import com.surf.ledger.auth.AuthInterceptor;
import com.surf.ledger.common.ApiException;
import com.surf.ledger.tx.TxDtos.BatchReq;
import com.surf.ledger.tx.TxDtos.BatchRes;
import com.surf.ledger.tx.TxDtos.EditReq;
import com.surf.ledger.tx.TxDtos.SummaryRow;
import com.surf.ledger.tx.TxDtos.TxRow;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TxController {
    private final TxService service;

    public TxController(TxService service) {
        this.service = service;
    }

    @PostMapping("/transactions/batch")
    public BatchRes batch(@RequestAttribute(AuthInterceptor.UID) long uid,
                          @RequestBody BatchReq req) {
        if (req.items() == null || req.items().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_ITEMS");
        }
        return new BatchRes(service.insertBatch(uid, req.items(), req.text(),
                req.raw(), req.model(), Boolean.TRUE.equals(req.edited()), req.source()));
    }

    /**
     * GET /api/transactions?from=2026-11-01&to=2026-11-30&q=火锅&type=expense&limit=500
     * 也兼容 ?month=2026-11；都不传时返回最近 500 条。
     */
    @GetMapping("/transactions")
    public List<TxRow> list(@RequestAttribute(AuthInterceptor.UID) long uid,
                            @RequestParam(required = false) String month,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to,
                            @RequestParam(required = false) String q,
                            @RequestParam(required = false) String type,
                            @RequestParam(required = false) String category,
                            @RequestParam(required = false) Integer limit) {
        return service.list(uid, month, from, to, q, type, category, limit);
    }

    @PatchMapping("/transactions/{id}")
    public TxRow update(@RequestAttribute(AuthInterceptor.UID) long uid,
                        @PathVariable long id,
                        @RequestBody EditReq req) {
        return service.update(uid, id, req);
    }

    @DeleteMapping("/transactions/{id}")
    public Map<String, Object> delete(@RequestAttribute(AuthInterceptor.UID) long uid,
                                      @PathVariable long id) {
        service.softDelete(uid, id);
        return Map.of("ok", true);
    }

    @GetMapping(value = "/transactions/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(@RequestAttribute(AuthInterceptor.UID) long uid,
                                         @RequestParam(required = false) String month,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to) {
        String csv = service.exportCsv(uid, month, from, to);
        String scope;
        if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
            scope = from.trim() + "_" + to.trim();
        } else {
            scope = (month == null || month.isBlank()) ? "all" : month.trim();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ledger-" + scope + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/summary")
    public List<SummaryRow> summary(@RequestAttribute(AuthInterceptor.UID) long uid,
                                    @RequestParam(required = false) String month) {
        return service.summary(uid, month);
    }
}
