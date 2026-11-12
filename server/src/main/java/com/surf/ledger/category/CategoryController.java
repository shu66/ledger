package com.surf.ledger.category;

import com.surf.ledger.auth.AuthInterceptor;
import com.surf.ledger.category.CategoryService.Category;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CategoryController {
    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    public record CreateReq(String name, String type, String icon) {}

    @GetMapping("/categories")
    public List<Category> all(@RequestAttribute(AuthInterceptor.UID) long uid) {
        return service.list(uid);
    }

    @PostMapping("/categories")
    public Category create(@RequestAttribute(AuthInterceptor.UID) long uid,
                           @RequestBody CreateReq req) {
        return service.create(uid, req.name(), req.type(), req.icon());
    }

    @DeleteMapping("/categories/{id}")
    public Map<String, Object> delete(@RequestAttribute(AuthInterceptor.UID) long uid,
                                      @PathVariable String id) {
        service.delete(uid, id);
        return Map.of("ok", true);
    }
}
