package com.surf.ledger.ai;

import com.surf.ledger.common.ApiException;
import com.surf.ledger.common.Crypto;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class LlmConfigService {
    public record LlmConfig(long id, String name, String baseUrl, String model, String apiKey) {}

    private final JdbcTemplate jdbc;
    private final Crypto crypto;
    private final LlmClient client;

    private volatile List<LlmConfig> cache = List.of();
    private volatile long cacheAt = 0;
    private static final long TTL = 30_000;

    public LlmConfigService(JdbcTemplate jdbc, Crypto crypto, LlmClient client) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.client = client;
    }

    public List<Map<String, Object>> listMasked() {
        return jdbc.queryForList("""
                SELECT id, name, base_url, model, api_key_hint, enabled, is_default, updated_at
                FROM llm_configs ORDER BY is_default DESC, id
                """);
    }

    @Transactional
    public long create(String name, String baseUrl, String model, String apiKey, boolean isDefault) {
        require(name, baseUrl, model, apiKey);
        if (isDefault) jdbc.update("UPDATE llm_configs SET is_default = 0");

        String cipher = crypto.encrypt(apiKey);
        String keyHint = hint(apiKey);

        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO llm_configs(name, base_url, model, api_key_cipher, api_key_hint, is_default)
                    VALUES (?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, baseUrl);
            ps.setString(3, model);
            ps.setString(4, cipher);
            ps.setString(5, keyHint);
            ps.setBoolean(6, isDefault);
            return ps;
        }, kh);

        cache = List.of();
        return kh.getKey().longValue();
    }

    @Transactional
    public void update(long id, String name, String baseUrl, String model,
                       String apiKey, Boolean enabled, Boolean isDefault) {
        Map<String, Object> old = one(id);

        String cipher = (apiKey == null || apiKey.isBlank())
                ? String.valueOf(old.get("api_key_cipher"))
                : crypto.encrypt(apiKey);
        String keyHint = (apiKey == null || apiKey.isBlank())
                ? (String) old.get("api_key_hint")
                : hint(apiKey);

        if (Boolean.TRUE.equals(isDefault)) {
            jdbc.update("UPDATE llm_configs SET is_default = 0");
        }

        jdbc.update("""
                UPDATE llm_configs SET
                  name = COALESCE(?, name),
                  base_url = COALESCE(?, base_url),
                  model = COALESCE(?, model),
                  api_key_cipher = ?,
                  api_key_hint = ?,
                  enabled = COALESCE(?, enabled),
                  is_default = COALESCE(?, is_default),
                  updated_at = NOW(3)
                WHERE id = ?
                """, name, baseUrl, model, cipher, keyHint, enabled, isDefault, id);

        cache = List.of();
    }

    @Transactional
    public void delete(long id) {
        jdbc.update("DELETE FROM llm_configs WHERE id = ?", id);
        cache = List.of();
    }

    public Map<String, Object> test(long id) {
        LlmConfig cfg = load(id);
        long t0 = System.currentTimeMillis();
        // 连通性测试不走 JSON 模式：json_object 会强制 prompt 带 "json" 字样，且回复内容不好看
        String reply = client.chat(cfg, "你是一个测试助手。", "只回复两个字：正常", false);
        return Map.of("ok", true, "latencyMs", System.currentTimeMillis() - t0,
                "reply", reply.length() > 60 ? reply.substring(0, 60) : reply);
    }

    /** 默认配置优先，其余启用的按 id 排；前两个用于失败降级 */
    public List<LlmConfig> candidates() {
        long now = System.currentTimeMillis();
        if (now - cacheAt < TTL && !cache.isEmpty()) return cache;

        List<LlmConfig> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT id, name, base_url, model, api_key_cipher
                FROM llm_configs WHERE enabled = 1
                ORDER BY is_default DESC, id
                """)) {
            out.add(new LlmConfig(
                    ((Number) r.get("id")).longValue(),
                    (String) r.get("name"),
                    (String) r.get("base_url"),
                    (String) r.get("model"),
                    crypto.decrypt((String) r.get("api_key_cipher"))));
        }
        cache = out;
        cacheAt = now;
        return out;
    }

    private LlmConfig load(long id) {
        Map<String, Object> r = one(id);
        return new LlmConfig(id, (String) r.get("name"), (String) r.get("base_url"),
                (String) r.get("model"), crypto.decrypt((String) r.get("api_key_cipher")));
    }

    private Map<String, Object> one(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM llm_configs WHERE id = ?", id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "CONFIG_NOT_FOUND");
        return rows.get(0);
    }

    private void require(String name, String baseUrl, String model, String apiKey) {
        if (name == null || name.isBlank() || baseUrl == null || baseUrl.isBlank()
                || model == null || model.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_FIELD");
        }
    }

    private String hint(String apiKey) {
        return apiKey.length() <= 4 ? "****" : "****" + apiKey.substring(apiKey.length() - 4);
    }
}
