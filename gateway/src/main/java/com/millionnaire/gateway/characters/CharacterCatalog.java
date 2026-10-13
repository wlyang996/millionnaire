package com.millionnaire.gateway.characters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.gateway.room.ClientException;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Whole packs are immutable after publication; active pointer and snapshot commit atomically. */
@Component
public class CharacterCatalog {
    public record Asset(String key, String sha256, int width, int height, int bytes) {}
    public record Entry(int id, String name, int sort, boolean enabled, int fallback, String packId, Map<String, Asset> images) {
        public Entry { images = Map.copyOf(images); }
    }
    public record Catalog(long version, List<Entry> characters) {
        public Catalog { characters = List.copyOf(characters); }
    }
    public record Pack(String packId, Entry character, int revision, boolean published, long createdAt) {}
    private final Supplier<JdbcTemplate> jdbc;
    private final CharacterObjectStore objects;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Pack> packs = new HashMap<>();
    private final Map<Long, Catalog> catalogs = new HashMap<>();
    private long active = 0, nextVersion = 1;
    private static final String[] NAMES = {"糖糖", "可可", "阿杰", "奶茶", "阿凯", "圆圆", "豆豆", "毛毛"};

    @Autowired public CharacterCatalog(ObjectProvider<JdbcTemplate> jdbc, CharacterObjectStore objects, Clock clock) {
        this(jdbc::getIfAvailable, objects, clock);
    }
    public CharacterCatalog(Supplier<JdbcTemplate> jdbc, CharacterObjectStore objects, Clock clock) {
        this.jdbc = jdbc; this.objects = objects; this.clock = clock;
    }
    public static Catalog defaults() {
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) out.add(new Entry(i, NAMES[i], i, true, i, null, Map.of()));
        return new Catalog(0, out);
    }
    public boolean storageReady() { return objects.ready(); }
    public void verifyStorage() { objects.verify(); }
    public boolean persistent() { return jdbc.get() != null; }
    public synchronized long activeId() {
        var t = jdbc.get();
        return t == null ? active : t.queryForObject("SELECT version_id FROM character_catalog_active WHERE slot='default'", Long.class);
    }
    public synchronized Catalog current() { return load(activeId()); }
    public synchronized Catalog load(long version) {
        if (version == 0) return defaults();
        Catalog hit = catalogs.get(version);
        if (hit != null) return hit;
        var t = jdbc.get();
        if (t == null) {
            var c = catalogs.get(version); if (c == null) throw missing(); return c;
        }
        var rows = t.queryForList("SELECT payload FROM character_catalog WHERE version_id=?", String.class, version);
        if (rows.isEmpty()) throw missing();
        Catalog loaded = read(rows.get(0), Catalog.class);
        catalogs.put(version, loaded);
        return loaded;
    }
    /** Disabled identities stay valid for display, but cannot be newly selected. */
    public boolean selectable(int avatar) {
        return avatar != 8 && current().characters().stream().anyMatch(c -> c.id() == avatar && c.enabled());
    }
    public int forRoom(int avatar, long version) {
        if (avatar == 8) return 8;
        return load(version).characters().stream().anyMatch(c -> c.id() == avatar) ? avatar : 0;
    }
    public synchronized List<Pack> packs() {
        var t = jdbc.get();
        return t == null ? packs.values().stream().sorted(Comparator.comparingLong(Pack::createdAt).reversed()).toList()
                : t.query("SELECT payload FROM character_pack ORDER BY created_at DESC", (rs, i) -> read(rs.getString(1), Pack.class));
    }
    public synchronized Pack pack(String id) {
        var t = jdbc.get();
        if (t == null) { var p = packs.get(id); if (p == null) throw missing(); return p; }
        var rows = t.queryForList("SELECT payload FROM character_pack WHERE pack_id=?", String.class, id);
        if (rows.isEmpty()) throw missing();
        return read(rows.get(0), Pack.class);
    }
    public synchronized Pack create(Integer avatar, String name, int sort, boolean enabled, int fallback) {
        return transaction(() -> {
            validateMetadata(name, sort, fallback);
            var all = packs();
            int id = avatar == null ? Math.max(9, all.stream().mapToInt(p -> p.character().id() + 1).max().orElse(9)) : avatar;
            if (id < 0 || id > 998 || id == 8) throw bad("人物 ID 不可为机器人编号，且须在 0～998 内");
            if (avatar != null && current().characters().stream().noneMatch(c -> c.id() == id)
                    && all.stream().noneMatch(p -> p.character().id() == id)) throw bad("未知人物 ID；新建人物请留空 ID");
            Pack p = new Pack(UUID.randomUUID().toString(), new Entry(id, name.strip(), sort, enabled, fallback, null, Map.of()), 0, false, clock.millis());
            var t = jdbc.get();
            if (t == null) packs.put(p.packId(), p);
            else t.update("INSERT INTO character_pack (pack_id,avatar_index,payload,published,revision,created_at) VALUES (?,?,?,0,0,?)",
                    p.packId(), id, write(p), p.createdAt());
            return p;
        });
    }
    public synchronized Pack edit(String id, int revision, String name, int sort, boolean enabled, int fallback) {
        validateMetadata(name, sort, fallback);
        Pack p = editable(id, revision); var c = p.character();
        return save(new Pack(id, new Entry(c.id(), name.strip(), sort, enabled, fallback, null, c.images()), revision + 1, false, p.createdAt()), revision);
    }
    public Pack upload(String id, String slot, int revision, byte[] png) {
        Pack p = editable(id, revision);
        var info = CharacterImages.inspect(slot, png);
        String key = "characters/" + p.character().id() + "/" + id + "/" + slot + "-" + info.sha256() + ".png";
        objects.put(key, png); // only commit metadata after COS confirms upload
        var assets = new HashMap<>(p.character().images());
        assets.put(slot, new Asset(key, info.sha256(), info.width(), info.height(), info.bytes()));
        var c = p.character();
        return save(new Pack(id, new Entry(c.id(), c.name(), c.sort(), c.enabled(), c.fallback(), null, assets), revision + 1, false, p.createdAt()), revision);
    }
    public Catalog publish(String id, int revision, long expectedVersion) {
        Pack verified = editable(id, revision);
        var images = verified.character().images();
        if (!images.keySet().containsAll(CharacterImages.SLOTS) || !CharacterImages.ALL_SLOTS.containsAll(images.keySet())) throw bad("必须上传完整七项基础素材才可发布");
        if (CharacterImages.MOTION_SLOTS.stream().anyMatch(images::containsKey)) {
            if (!images.keySet().containsAll(CharacterImages.MOTION_SLOTS)) throw bad("移动动作须完整上传蓄力、腾空、落地和准备四张，不能混用其他人物");
            var pawn = images.get("pawn");
            if (CharacterImages.MOTION_SLOTS.stream().map(images::get).anyMatch(a -> a.width() != pawn.width() || a.height() != pawn.height())) throw bad("四张移动姿态须与棋盘棋子使用相同画布尺寸，人物脚底位置须在预览中对齐");
        }
        // COS IO must not hold the catalog monitor or database row lock used by active rooms.
        for (var e : verified.character().images().entrySet()) {
            var check = CharacterImages.inspect(e.getKey(), objects.get(e.getValue().key()));
            if (!check.sha256().equals(e.getValue().sha256())) throw bad("COS 素材校验失败，请重新上传");
        }
        synchronized (this) {
        return transaction(() -> {
            stale(expectedVersion);
            // The verified revision must still be current after the remote reads.
            Pack p = editable(id, revision);
            var list = new ArrayList<>(current().characters());
            var c = p.character();
            list.removeIf(e -> e.id() == c.id());
            list.add(new Entry(c.id(), c.name(), c.sort(), c.enabled(), c.fallback(), id, c.images()));
            if (list.stream().noneMatch(Entry::enabled)) throw bad("至少保留一位可选人物");
            var frozen = new Pack(id, c, p.revision() + 1, true, p.createdAt());
            var t = jdbc.get();
            if (t == null) packs.put(id, frozen);
            else if (t.update("UPDATE character_pack SET payload=?,published=1,revision=? WHERE pack_id=? AND revision=? AND published=0",
                    write(frozen), frozen.revision(), id, revision) != 1) throw bad("素材草稿已变化，请刷新");
            return release(list);
        });
        }
    }
    public synchronized Catalog rollback(long target, long expectedVersion) {
        return transaction(() -> { stale(expectedVersion); return release(load(target).characters()); });
    }
    /** Metadata edits retain the complete immutable image set; they never partially replace pictures. */
    public synchronized Catalog configure(int id, String name, int sort, boolean enabled, long expectedVersion) {
        validateMetadata(name, sort, 0);
        return transaction(() -> {
            stale(expectedVersion);
            var list = new ArrayList<>(current().characters());
            var old = list.stream().filter(e -> e.id() == id).findFirst().orElseThrow(CharacterCatalog::missing);
            list.remove(old);
            list.add(new Entry(id,name.strip(),sort,enabled,old.fallback(),old.packId(),old.images()));
            if (list.stream().noneMatch(Entry::enabled)) throw bad("至少保留一位可选人物");
            return release(list);
        });
    }
    public synchronized List<Catalog> history() {
        var t = jdbc.get();
        var out = new ArrayList<>(t == null ? catalogs.values().stream().sorted(Comparator.comparingLong(Catalog::version).reversed()).limit(50).toList()
                : t.query("SELECT payload FROM character_catalog ORDER BY version_id DESC LIMIT 50", (rs, i) -> read(rs.getString(1), Catalog.class)));
        out.add(defaults()); return out;
    }
    public byte[] image(String packId, String slot, boolean draftAllowed) {
        if (!CharacterImages.ALL_SLOTS.contains(slot)) throw missing();
        Pack p = pack(packId);
        if (!draftAllowed && !p.published()) throw missing();
        Asset a = p.character().images().get(slot);
        if (a == null) throw missing();
        byte[] bytes = objects.get(a.key());
        if (!CharacterImages.inspect(slot, bytes).sha256().equals(a.sha256())) throw bad("图片完整性校验失败");
        return bytes;
    }
    public Map<String,Object> client(Catalog catalog) {
        return Map.of("version", catalog.version(), "characters", catalog.characters().stream()
                .sorted(Comparator.comparingInt(Entry::sort).thenComparingInt(Entry::id)).map(c -> {
                    Map<String,Object> imgs = new LinkedHashMap<>();
                    c.images().forEach((slot, a) -> imgs.put(slot, Map.of("url", "/api/characters/assets/" + c.packId() + "/" + slot + ".png",
                            "sha256", a.sha256(), "width", a.width(), "height", a.height())));
                    return Map.of("id", c.id(), "name", c.name(), "sort", c.sort(), "enabled", c.enabled(),
                            "fallback", c.fallback(), "bundled", c.packId() == null, "images", imgs);
                }).toList());
    }
    private Pack editable(String id, int revision) {
        Pack p = pack(id);
        if (p.published()) throw bad("已发布素材不可修改，请新建一套版本");
        if (p.revision() != revision) throw bad("草稿已变化，请刷新");
        return p;
    }
    private synchronized Pack save(Pack p, int previous) {
        var t = jdbc.get();
        if (t == null) { editable(p.packId(), previous); packs.put(p.packId(), p); }
        else if (t.update("UPDATE character_pack SET payload=?,revision=? WHERE pack_id=? AND revision=? AND published=0",
                write(p), p.revision(), p.packId(), previous) != 1) throw bad("草稿已变化，请刷新");
        return p;
    }
    private Catalog release(List<Entry> characters) {
        var t = jdbc.get();
        long version;
        if (t == null) { version = nextVersion++; active = version; }
        else {
            var keys = new GeneratedKeyHolder();
            t.update(c -> { var ps = c.prepareStatement("INSERT INTO character_catalog (payload,created_at) VALUES (?,?)", new String[]{"version_id"});
                ps.setString(1, "{}"); ps.setLong(2, clock.millis()); return ps; }, keys);
            version = keys.getKey().longValue();
        }
        Catalog catalog = new Catalog(version, characters);
        if (t == null) catalogs.put(version, catalog);
        else {
            t.update("UPDATE character_catalog SET payload=? WHERE version_id=?", write(catalog), version);
            t.update("UPDATE character_catalog_active SET version_id=? WHERE slot='default'", version);
        }
        return catalog;
    }
    private <T> T transaction(Supplier<T> action) {
        var t = jdbc.get(); if (t == null) return action.get();
        return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(t.getDataSource()))).execute(status -> {
            t.queryForObject("SELECT version_id FROM character_catalog_active WHERE slot='default' FOR UPDATE", Long.class);
            return action.get();
        });
    }
    private void stale(long expected) { if (activeId() != expected) throw new ClientException("STALE_CATALOG", "生效素材版本已变化，请刷新"); }
    private static void validateMetadata(String name, int sort, int fallback) {
        if (name == null || name.strip().isEmpty() || name.codePointCount(0, name.length()) > 20 || name.codePoints().anyMatch(Character::isISOControl)) throw bad("名称须为 1～20 字且不能包含控制符");
        if (sort < 0 || sort > 9999 || fallback < 0 || fallback > 7) throw bad("排序须为 0～9999，备用人物须为 0～7");
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private <T> T read(String s, Class<T> type) { try { return json.readValue(s, type); } catch (Exception e) { throw new IllegalStateException("Invalid character metadata", e); } }
    private static ClientException bad(String s) { return new ClientException("INVALID_CHARACTER", s); }
    private static ClientException missing() { return new ClientException("NOT_FOUND", "人物素材或版本不存在"); }
}
