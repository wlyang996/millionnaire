package com.millionnaire.gateway.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.engine.config.ConfigValidator;
import com.millionnaire.engine.core.engine.Engine;
import com.millionnaire.engine.core.engine.SessionDomain;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.gateway.room.ClientException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/**
 * 游戏参数的发布版本（表 game_config / game_config_active，见 V2__game_config.sql）。
 * <ul>
 *   <li>configId 0 = 内置默认配置（未发布过任何版本时使用）；</li>
 *   <li>新建房间读取当前生效版本并绑定其引擎，对局中途发布不影响已开的房间；</li>
 *   <li>未启用 db 配置时只存在内存里，重启丢失（本地开发用）。</li>
 * </ul>
 */
@Component
public class GameConfigs {
    public static final long DEFAULT_ID = 0;
    static final String SLOT = "default";

    /** 一个已发布版本。 */
    public record Published(long configId, GameSettings settings, String ruleHash, String note, String createdBy,
                            long createdAt, Long sourceConfigId) {
    }

    /** 已发布版本 + 绑定的引擎。 */
    public record Active(long configId, GameSettings settings, Engine<SessionState> engine) {
    }

    private final Supplier<JdbcTemplate> jdbc;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Long, Active> cache = new ConcurrentHashMap<>();
    private final Map<Long, Published> memory = new ConcurrentHashMap<>();
    private volatile long memoryActive = DEFAULT_ID;
    private long memoryNext = 1;

    @Autowired
    public GameConfigs(ObjectProvider<JdbcTemplate> jdbc, Clock clock) {
        this(jdbc::getIfAvailable, clock);
    }

    private GameConfigs(Supplier<JdbcTemplate> jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        GameSettings d = SettingsMapper.defaults();
        cache.put(DEFAULT_ID, new Active(DEFAULT_ID, d,
                new Engine<>(SettingsMapper.toRuleConfig(d), SessionDomain.INSTANCE)));
    }

    /** 不连数据库（测试与本地开发）：发布只存在内存里。 */
    public static GameConfigs inMemory(Clock clock) {
        return new GameConfigs(() -> null, clock);
    }

    /** 当前生效版本（新建房间用）。 */
    public Active current() {
        return byId(activeId()).orElseGet(() -> cache.get(DEFAULT_ID));
    }

    public long activeId() {
        JdbcTemplate t = jdbc.get();
        if (t == null) {
            return memoryActive;
        }
        List<Long> ids = t.queryForList("SELECT config_id FROM game_config_active WHERE slot = ?", Long.class, SLOT);
        return ids.isEmpty() ? DEFAULT_ID : ids.get(0);
    }

    public Optional<Active> byId(long configId) {
        Active hit = cache.get(configId);
        if (hit != null) {
            return Optional.of(hit);
        }
        return load(configId).map(p -> cache.computeIfAbsent(configId, id -> new Active(id, p.settings(),
                new Engine<>(ConfigValidator.validateOrThrow(SettingsMapper.toRuleConfig(p.settings())),
                        SessionDomain.INSTANCE))));
    }

    public Optional<Published> load(long configId) {
        if (configId == DEFAULT_ID) {
            return Optional.of(defaultPublished());
        }
        JdbcTemplate t = jdbc.get();
        if (t == null) {
            return Optional.ofNullable(memory.get(configId));
        }
        List<Published> rows = t.query("SELECT config_id, payload, rule_hash, note, created_by, created_at,"
                + " source_config_id FROM game_config WHERE config_id = ?", (rs, i) -> new Published(
                rs.getLong(1), parse(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getLong(6), (Long) rs.getObject(7, Long.class)), configId);
        return rows.stream().findFirst();
    }

    /** 发布历史，新的在前（不含 payload 之外的大字段裁剪，测试版条数很少）。 */
    public List<Published> history(int limit) {
        JdbcTemplate t = jdbc.get();
        List<Published> out = new ArrayList<>();
        if (t == null) {
            memory.values().stream().sorted((a, b) -> Long.compare(b.configId(), a.configId())).limit(limit)
                    .forEach(out::add);
        } else {
            out.addAll(t.query("SELECT config_id, payload, rule_hash, note, created_by, created_at, source_config_id"
                    + " FROM game_config ORDER BY config_id DESC LIMIT ?", (rs, i) -> new Published(
                    rs.getLong(1), parse(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getLong(6), (Long) rs.getObject(7, Long.class)), limit));
        }
        if (out.size() < limit) {
            out.add(defaultPublished());
        }
        return out;
    }

    /**
     * 校验并发布为新版本，立即生效。expectedActiveId 与当前生效版本不一致时拒绝（另一个人刚发布过）。
     */
    public synchronized Published publish(GameSettings settings, String note, String by, long expectedActiveId,
                                          Long sourceConfigId) {
        List<String> errors = SettingsMapper.validate(settings);
        if (!errors.isEmpty()) {
            throw new ClientException("INVALID_CONFIG", String.join("；", errors));
        }
        long current = activeId();
        if (current != expectedActiveId) {
            throw new ClientException("STALE_CONFIG", "当前生效版本已变为 #" + current + "，请刷新后再发布");
        }
        GameSettings s = SettingsMapper.normalized(settings);
        String ruleHash = SettingsMapper.toRuleConfig(s).contentHash();
        String payload = write(s);
        String n = note == null ? "" : note.strip();
        if (n.length() > 200) {
            n = n.substring(0, 200);
        }
        long now = clock.millis();
        JdbcTemplate t = jdbc.get();
        long id;
        if (t == null) {
            id = memoryNext++;
            memory.put(id, new Published(id, s, ruleHash, n, by, now, sourceConfigId));
            memoryActive = id;
        } else {
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            String fn = n;
            t.update(c -> {
                var ps = c.prepareStatement("INSERT INTO game_config (payload, rule_hash, note, created_by,"
                        + " created_at, source_config_id) VALUES (?, ?, ?, ?, ?, ?)", new String[]{"config_id"});
                ps.setString(1, payload);
                ps.setString(2, ruleHash);
                ps.setString(3, fn);
                ps.setString(4, by);
                ps.setLong(5, now);
                if (sourceConfigId == null) {
                    ps.setNull(6, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(6, sourceConfigId);
                }
                return ps;
            }, keys);
            id = keys.getKey().longValue();
            int updated = t.update("UPDATE game_config_active SET config_id = ?, updated_at = ? WHERE slot = ?",
                    id, now, SLOT);
            if (updated == 0) {
                t.update("INSERT INTO game_config_active (slot, config_id, updated_at) VALUES (?, ?, ?)",
                        SLOT, id, now);
            }
        }
        return new Published(id, s, ruleHash, n, by, now, sourceConfigId);
    }

    /** 回滚 = 以旧版本内容再发布一个新版本。 */
    public Published rollback(long configId, String by, long expectedActiveId) {
        Published old = load(configId).orElseThrow(() ->
                new ClientException("NOT_FOUND", "没有版本 #" + configId));
        String label = configId == DEFAULT_ID ? "内置默认配置" : "版本 #" + configId;
        return publish(old.settings(), "回滚到" + label, by, expectedActiveId, configId);
    }

    private Published defaultPublished() {
        GameSettings d = cache.get(DEFAULT_ID).settings();
        return new Published(DEFAULT_ID, d, cache.get(DEFAULT_ID).engine().configHash(), "内置默认配置", "system",
                0, null);
    }

    private GameSettings parse(String payload) {
        try {
            GameSettings s = json.readValue(payload, GameSettings.class);
            // 旧版本没有奖池或只有幸运奖池：按内置默认补齐（引擎换算时同样补齐，规则哈希不变）
            return new GameSettings(s.tiers(), s.station(), s.fees(), s.eventCash(), s.eventWeights(), s.cardWeights(),
                    s.tileNames(), SettingsMapper.mergeLucky(s.lucky()), SettingsMapper.rentRiseOf(s),
                    SettingsMapper.handLimitOf(s));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("bad game_config payload", e);
        }
    }

    private String write(GameSettings s) {
        try {
            return json.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
