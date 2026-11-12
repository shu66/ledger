package com.surf.ledger.admin;

import com.surf.ledger.ai.LlmConfigService;
import com.surf.ledger.auth.AuthInterceptor;
import com.surf.ledger.common.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
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

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final AdminService admin;
    private final LlmConfigService llm;
    private final ClientIp clientIp;

    public AdminController(AdminService admin, LlmConfigService llm, ClientIp clientIp) {
        this.admin = admin;
        this.llm = llm;
        this.clientIp = clientIp;
    }

    public record PatchUserReq(String role, String status) {}
    public record LlmReq(String name, String baseUrl, String model,
                         String apiKey, Boolean enabled, Boolean isDefault) {}

    @GetMapping("/users")
    public List<Map<String, Object>> users(@RequestParam(required = false) String q) {
        return admin.users(q, 200);
    }

    @PatchMapping("/users/{id}")
    public Map<String, Object> patchUser(@RequestAttribute(AuthInterceptor.UID) long actor,
                                         @PathVariable long id,
                                         @RequestBody PatchUserReq req,
                                         HttpServletRequest http) {
        admin.patchUser(actor, id, req.role(), req.status());
        admin.audit(actor, "user.patch", String.valueOf(id),
                "role=" + req.role() + " status=" + req.status(), clientIp.of(http));
        return Map.of("ok", true);
    }

    @PostMapping("/users/{id}/reset-password")
    public Map<String, Object> resetPassword(@RequestAttribute(AuthInterceptor.UID) long actor,
                                             @PathVariable long id,
                                             HttpServletRequest http) {
        String temp = admin.resetPassword(id);
        admin.audit(actor, "user.reset_password", String.valueOf(id), null, clientIp.of(http));
        return Map.of("tempPassword", temp);
    }

    @GetMapping("/llm-configs")
    public List<Map<String, Object>> configs() {
        return llm.listMasked();
    }

    @PostMapping("/llm-configs")
    public Map<String, Object> create(@RequestAttribute(AuthInterceptor.UID) long actor,
                                      @RequestBody LlmReq req,
                                      HttpServletRequest http) {
        long id = llm.create(req.name(), req.baseUrl(), req.model(), req.apiKey(),
                Boolean.TRUE.equals(req.isDefault()));
        admin.audit(actor, "llm.create", String.valueOf(id), req.name(), clientIp.of(http));
        return Map.of("id", id);
    }

    @PatchMapping("/llm-configs/{id}")
    public Map<String, Object> update(@RequestAttribute(AuthInterceptor.UID) long actor,
                                      @PathVariable long id,
                                      @RequestBody LlmReq req,
                                      HttpServletRequest http) {
        llm.update(id, req.name(), req.baseUrl(), req.model(), req.apiKey(),
                req.enabled(), req.isDefault());
        admin.audit(actor, "llm.update", String.valueOf(id), req.name(), clientIp.of(http));
        return Map.of("ok", true);
    }

    @DeleteMapping("/llm-configs/{id}")
    public Map<String, Object> delete(@RequestAttribute(AuthInterceptor.UID) long actor,
                                      @PathVariable long id,
                                      HttpServletRequest http) {
        llm.delete(id);
        admin.audit(actor, "llm.delete", String.valueOf(id), null, clientIp.of(http));
        return Map.of("ok", true);
    }

    @PostMapping("/llm-configs/{id}/test")
    public Map<String, Object> test(@PathVariable long id) {
        return llm.test(id);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return admin.stats();
    }

    @GetMapping("/audit-logs")
    public List<Map<String, Object>> logs() {
        return admin.auditLogs(100);
    }
}
