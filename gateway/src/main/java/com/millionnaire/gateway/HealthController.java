package com.millionnaire.gateway;

import com.millionnaire.engine.EngineVersion;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final ObjectProvider<JdbcTemplate> jdbc;

    public HealthController(ObjectProvider<JdbcTemplate> jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "engine", EngineVersion.VALUE);
    }

    /** 数据库连通诊断：未启用 db profile 返回 DISABLED；连库或查表失败返回 DOWN 与异常类型（不含密码）。 */
    @GetMapping("/health/db")
    public Map<String, Object> db() {
        Map<String, Object> out = new LinkedHashMap<>();
        JdbcTemplate t = jdbc.getIfAvailable();
        if (t == null) {
            out.put("db", "DISABLED");
            return out;
        }
        try {
            out.put("server", t.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductVersion()));
            out.put("flywayVersion", t.queryForObject(
                    "SELECT MAX(version) FROM flyway_schema_history WHERE success = 1", String.class));
            out.put("appUserRows", t.queryForObject("SELECT COUNT(*) FROM app_user", Long.class));
            out.put("db", "UP");
        } catch (RuntimeException e) {
            out.put("db", "DOWN");
            out.put("error", e.getClass().getSimpleName());
        }
        return out;
    }
}
