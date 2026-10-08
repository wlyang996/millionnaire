package com.millionnaire.gateway.config;

import com.millionnaire.gateway.room.ClientException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台接口（页面在 /admin）。测试版：单一管理员密码，来自环境变量 ADMIN_PASSWORD；不配置时后台不可用。
 * 登录得到 12 小时有效的令牌（只在内存里，后台重启后需重新登录）。
 */
@RestController
@RequestMapping("/admin-api/v1")
public class AdminController {
    static final long TOKEN_TTL_MS = 12 * 3600 * 1000L;
    /** 连续输错这么多次后锁定一段时间（整个后台共用，测试版只有一个账号）。 */
    static final int MAX_FAILURES = 5;
    static final long LOCK_MS = 5 * 60 * 1000L;

    private final GameConfigs configs;
    private final com.millionnaire.gateway.record.Dashboard dashboard;
    private final Clock clock;
    private final byte[] password;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private int failures;
    private long lockedUntil;

    public AdminController(GameConfigs configs, com.millionnaire.gateway.record.Dashboard dashboard, Clock clock,
                           @Value("${millionnaire.admin.password:}") String password) {
        this.configs = configs;
        this.dashboard = dashboard;
        this.clock = clock;
        this.password = password == null ? new byte[0] : password.getBytes(StandardCharsets.UTF_8);
    }

    public record Login(String password) {
    }

    @PostMapping("/auth/login")
    public synchronized ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) Login body) {
        if (password.length == 0) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "ADMIN_DISABLED", "未配置管理员密码（环境变量 ADMIN_PASSWORD）");
        }
        long now = clock.millis();
        if (now < lockedUntil) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "LOCKED", "密码错误次数过多，请 5 分钟后再试");
        }
        byte[] given = body == null || body.password() == null ? new byte[0]
                : body.password().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, password)) {
            if (++failures >= MAX_FAILURES) {
                failures = 0;
                lockedUntil = now + LOCK_MS;
            }
            return error(HttpStatus.FORBIDDEN, "BAD_PASSWORD", "密码错误");
        }
        failures = 0;
        tokens.values().removeIf(exp -> exp < now);
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.put(token, now + TOKEN_TTL_MS);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("expiresAt", now + TOKEN_TTL_MS);
        return ResponseEntity.ok(out);
    }

    /** 当前生效版本：{configId, settings, note, createdAt}。 */
    @GetMapping("/config/active")
    public ResponseEntity<?> active(@RequestHeader(value = "Authorization", required = false) String auth) {
        check(auth);
        long id = configs.activeId();
        return ResponseEntity.ok(summary(configs.load(id).orElseThrow(), true));
    }

    /** 内置默认参数（"恢复默认值"按钮用）。 */
    @GetMapping("/config/defaults")
    public ResponseEntity<?> defaults(@RequestHeader(value = "Authorization", required = false) String auth) {
        check(auth);
        return ResponseEntity.ok(SettingsMapper.defaults());
    }

    @PostMapping("/config/validate")
    public ResponseEntity<?> validate(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody(required = false) GameSettings settings) {
        check(auth);
        return ResponseEntity.ok(Map.of("errors", SettingsMapper.validate(settings)));
    }

    public record Publish(GameSettings settings, String note, Long expectedActiveId) {
    }

    @PostMapping("/config/publish")
    public ResponseEntity<?> publish(@RequestHeader(value = "Authorization", required = false) String auth,
                                     @RequestBody(required = false) Publish body) {
        check(auth);
        if (body == null || body.expectedActiveId() == null) {
            throw new ClientException("BAD_REQUEST", "缺少 settings 或 expectedActiveId");
        }
        return ResponseEntity.ok(summary(configs.publish(body.settings(), body.note(), "admin",
                body.expectedActiveId(), null), false));
    }

    /** 运营数据看板：最近 days 天（北京时间，1～90，默认 14）。 */
    @GetMapping("/dashboard")
    public ResponseEntity<?> dashboard(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @org.springframework.web.bind.annotation.RequestParam(value = "days", defaultValue = "14") int days) {
        check(auth);
        return ResponseEntity.ok(dashboard.build(days));
    }

    @GetMapping("/config/history")
    public ResponseEntity<?> history(@RequestHeader(value = "Authorization", required = false) String auth) {
        check(auth);
        long active = configs.activeId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (GameConfigs.Published p : configs.history(50)) {
            Map<String, Object> m = summary(p, false);
            m.put("active", p.configId() == active);
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/config/{id}")
    public ResponseEntity<?> one(@RequestHeader(value = "Authorization", required = false) String auth,
                                 @PathVariable("id") long id) {
        check(auth);
        return configs.load(id).<ResponseEntity<?>>map(p -> ResponseEntity.ok(summary(p, true)))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "没有版本 #" + id));
    }

    public record Rollback(Long configId, Long expectedActiveId) {
    }

    @PostMapping("/config/rollback")
    public ResponseEntity<?> rollback(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody(required = false) Rollback body) {
        check(auth);
        if (body == null || body.configId() == null || body.expectedActiveId() == null) {
            throw new ClientException("BAD_REQUEST", "缺少 configId 或 expectedActiveId");
        }
        return ResponseEntity.ok(summary(configs.rollback(body.configId(), "admin", body.expectedActiveId()), false));
    }

    /** 令牌无效时抛出，由 {@link #onClient} 转为 401。 */
    private void check(String auth) {
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).strip() : null;
        Long exp = token == null ? null : tokens.get(token);
        if (exp == null || exp < clock.millis()) {
            if (token != null) {
                tokens.remove(token);
            }
            throw new Unauthorized();
        }
    }

    static final class Unauthorized extends RuntimeException {
        Unauthorized() {
            super(null, null, false, false);
        }
    }

    @ExceptionHandler(Unauthorized.class)
    ResponseEntity<Map<String, Object>> onUnauthorized() {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "登录已过期，请重新登录");
    }

    @ExceptionHandler(ClientException.class)
    ResponseEntity<Map<String, Object>> onClient(ClientException e) {
        HttpStatus status = "STALE_CONFIG".equals(e.code()) ? HttpStatus.CONFLICT
                : "NOT_FOUND".equals(e.code()) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return error(status, e.code(), e.getMessage());
    }

    private static Map<String, Object> summary(GameConfigs.Published p, boolean withSettings) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configId", p.configId());
        m.put("note", p.note());
        m.put("createdBy", p.createdBy());
        m.put("createdAt", p.createdAt());
        m.put("sourceConfigId", p.sourceConfigId());
        m.put("ruleHash", p.ruleHash());
        if (withSettings) {
            m.put("settings", p.settings());
        }
        return m;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("message", message);
        return ResponseEntity.status(status).body(out);
    }
}
