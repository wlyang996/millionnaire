package com.millionnaire.gateway.config;

import com.millionnaire.gateway.room.ClientException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;

/** Metadata-only history pages; never deserialize every published rules snapshot to search. */
public final class ConfigHistory {
    private ConfigHistory() { }

    public record Entry(long configId, String note, String createdBy, long createdAt,
                        Long sourceConfigId, String ruleHash, boolean active) {
        static Entry of(GameConfigs.Published p, long activeId) {
            return new Entry(p.configId(), p.note(), p.createdBy(), p.createdAt(),
                    p.sourceConfigId(), p.ruleHash(), p.configId() == activeId);
        }
    }
    public record Page(List<Entry> items, long total, int page, int size, long activeId) { }
    public record Query(int page, int size, String q, String type, String status) {
        public Query {
            q = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
            type = type == null ? "all" : type;
            status = status == null ? "all" : status;
            if (page < 1 || page > 1_000_000 || size < 1 || size > 100 || q.length() > 100
                    || !List.of("all", "publish", "rollback", "default").contains(type)
                    || !List.of("all", "active", "inactive").contains(status)) {
                throw new ClientException("BAD_REQUEST", "历史查询参数无效：页码须为1～1000000，每页1～100条，搜索最多100字");
            }
        }
        long offset() { return ((long) page - 1) * size; }
        Long id() {
            try { return q.matches("[0-9]+") ? Long.parseLong(q) : null; }
            catch (NumberFormatException ignored) { return null; }
        }
        String publisherAlias() { return q.equals("管理员") ? "admin" : q.equals("系统") ? "system" : null; }
        boolean matches(Entry entry) {
            if (type.equals("default") && entry.configId() != 0
                    || type.equals("publish") && (entry.configId() == 0 || entry.sourceConfigId() != null)
                    || type.equals("rollback") && entry.sourceConfigId() == null
                    || status.equals("active") && !entry.active()
                    || status.equals("inactive") && entry.active()) return false;
            Long id = id();
            return q.isEmpty() || lower(entry.note()).contains(q) || lower(entry.createdBy()).contains(q)
                    || publisherAlias() != null && publisherAlias().equals(lower(entry.createdBy()))
                    || id != null && (entry.configId() == id || id.equals(entry.sourceConfigId()));
        }
    }
    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }

    static Page memory(List<GameConfigs.Published> versions, GameConfigs.Published defaults, long activeId, Query query) {
        List<Entry> matched = new ArrayList<>(versions.stream().map(p -> Entry.of(p, activeId))
                .filter(query::matches).sorted((a, b) -> Long.compare(b.configId(), a.configId())).toList());
        Entry builtin = Entry.of(defaults, activeId);
        if (query.matches(builtin)) matched.add(builtin);
        return new Page(matched.stream().skip(query.offset()).limit(query.size()).toList(),
                matched.size(), query.page(), query.size(), activeId);
    }

    static Page database(JdbcTemplate jdbc, GameConfigs.Published defaults, long activeId, Query query) {
        StringBuilder where = new StringBuilder(" WHERE config_id > 0");
        List<Object> args = new ArrayList<>();
        switch (query.type()) {
            case "default" -> where.append(" AND 1 = 0");
            case "publish" -> where.append(" AND source_config_id IS NULL");
            case "rollback" -> where.append(" AND source_config_id IS NOT NULL");
            default -> { }
        }
        if (!query.status().equals("all")) {
            where.append(query.status().equals("active") ? " AND config_id = ?" : " AND config_id <> ?");
            args.add(activeId);
        }
        if (!query.q().isEmpty()) {
            where.append(" AND (LOWER(note) LIKE ? ESCAPE '!' OR LOWER(created_by) LIKE ? ESCAPE '!'");
            String pattern = "%" + query.q().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            args.add(pattern); args.add(pattern);
            if (query.publisherAlias() != null) {
                where.append(" OR LOWER(created_by) = ?"); args.add(query.publisherAlias());
            }
            if (query.id() != null) {
                where.append(" OR config_id = ? OR source_config_id = ?");
                args.add(query.id()); args.add(query.id());
            }
            where.append(")");
        }
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM game_config" + where, Long.class, args.toArray());
        List<Entry> items = new ArrayList<>();
        if (query.offset() < count) {
            List<Object> pageArgs = new ArrayList<>(args);
            pageArgs.add(query.size()); pageArgs.add(query.offset());
            items.addAll(jdbc.query("SELECT config_id, note, created_by, created_at, source_config_id, rule_hash"
                    + " FROM game_config" + where + " ORDER BY config_id DESC LIMIT ? OFFSET ?",
                    (rs, i) -> new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getObject(5, Long.class), rs.getString(6), rs.getLong(1) == activeId), pageArgs.toArray()));
        }
        Entry builtin = Entry.of(defaults, activeId);
        boolean includeDefault = query.matches(builtin);
        if (includeDefault && query.offset() <= count && query.offset() + query.size() > count) items.add(builtin);
        return new Page(List.copyOf(items), count + (includeDefault ? 1 : 0), query.page(), query.size(), activeId);
    }
}
