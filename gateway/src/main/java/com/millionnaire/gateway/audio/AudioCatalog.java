package com.millionnaire.gateway.audio;

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

/** Presentation settings have their own immutable releases, independent of game rule snapshots. */
@Component
public class AudioCatalog {
    @Autowired(required=false) private com.millionnaire.gateway.admin.MediaObjectRegistry mediaObjects;
    public static final Set<String> SCENES = Set.of("lobby", "room", "board", "game", "result");
    public static final Set<String> EFFECTS = Set.of("dice", "move", "cashGain", "cashLoss", "card", "purchase", "upgrade", "auction", "jail", "release", "reward");
    public record Binding(String assetId, boolean loop) {}
    public record Settings(boolean musicEnabled, int musicVolume, boolean effectsEnabled, int effectsVolume,
                           int fadeMs, Map<String, Binding> scenes, Map<String, String> effects) {
        public Settings { scenes = Map.copyOf(scenes); effects = Map.copyOf(effects); }
    }
    public record Asset(String id, String name, String kind, String key, AudioFiles.Info info, boolean builtin, long createdAt) {}
    public record Release(long version, Settings settings, long createdAt) {}
    public record Draft(long baseVersion, int revision, Settings settings) {}
    private final Supplier<JdbcTemplate> jdbc;
    private final AudioObjectStore objects;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Asset> builtin = new LinkedHashMap<>(), localAssets = new LinkedHashMap<>();
    private final Map<String,String> candidateBindings = new LinkedHashMap<>();
    private final Set<String> localPublished = new HashSet<>();
    private final Map<Long, Release> localHistory = new LinkedHashMap<>();
    private long active = 0, next = 1;
    private Draft localDraft = new Draft(0, 0, defaults());

    @Autowired public AudioCatalog(ObjectProvider<JdbcTemplate> jdbc, AudioObjectStore objects, Clock clock) {
        this(jdbc::getIfAvailable, objects, clock);
    }
    public AudioCatalog(Supplier<JdbcTemplate> jdbc, AudioObjectStore objects, Clock clock) {
        this.jdbc = jdbc; this.objects = objects; this.clock = clock;
        addBuiltin("morning-town", "晨光小镇"); addBuiltin("friends-play", "好友开局");
        Map<String,String> names=Map.ofEntries(Map.entry("dice","骰子轻碰"),Map.entry("move","棋子落地"),Map.entry("cashGain","收入金币"),Map.entry("cashLoss","支付金币"),Map.entry("card","翻牌"),Map.entry("purchase","购买成功"),Map.entry("upgrade","升级成功"),Map.entry("auction","拍卖落槌"),Map.entry("jail","入狱提示"),Map.entry("release","出狱提示"),Map.entry("reward","全员奖励"));
        for(String effect:EFFECTS.stream().sorted().toList()) {
            String key="audio/effects/cue-"+effect+"-v1.wav";
            String id=defaultEffectId(effect);
            candidateBindings.put(effect,id);builtin.put(id,new Asset(id,names.get(effect),"effect",key,AudioFiles.inspect(bundled(key)),true,0));
        }
    }
    private void addBuiltin(String id, String name) {
        String key = "audio/" + id + "-v1.wav";
        builtin.put(id, new Asset(id, name, "music", key, AudioFiles.inspect(bundled(key)), true, 0));
    }
    public static Settings defaults() {
        Map<String, Binding> scenes = new LinkedHashMap<>();
        for (String s : SCENES) scenes.put(s, new Binding(s.equals("lobby") || s.equals("board") ? "morning-town" : "friends-play", true));
        Map<String, String> effects = new LinkedHashMap<>(); EFFECTS.forEach(s -> effects.put(s, defaultEffectId(s)));
        return new Settings(true, 30, true, 65, 400, scenes, effects);
    }
    private static String defaultEffectId(String effect) { return UUID.nameUUIDFromBytes(("audio/effects/cue-"+effect+"-v1.wav").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); }
    public boolean persistent() { return jdbc.get() != null; }
    public boolean storageReady() { return objects.ready(); }
    private long activeId() {
        var t = jdbc.get();
        return t == null ? active : t.queryForObject("SELECT version_id FROM audio_active WHERE slot='default'", Long.class);
    }
    public synchronized Release current() { return load(activeId()); }
    public synchronized Release load(long version) {
        if (version == 0) return new Release(0, defaults(), 0);
        var t = jdbc.get();
        if (t == null) { var r = localHistory.get(version); if (r == null) throw missing(); return r; }
        var rows = t.queryForList("SELECT payload FROM audio_catalog WHERE version_id=?", String.class, version);
        if (rows.isEmpty()) throw missing();
        return read(rows.getFirst(), Release.class);
    }
    public synchronized Draft draft() {
        var t = jdbc.get();
        if (t == null) return localDraft;
        return t.queryForObject("SELECT version_id,draft_revision,draft_payload FROM audio_active WHERE slot='default'", (rs, i) ->
                new Draft(rs.getLong(1), rs.getInt(2), rs.getString(3) == null ? current().settings() : read(rs.getString(3), Settings.class)));
    }
    public synchronized List<Asset> assets() {
        var out = new ArrayList<>(builtin.values()); var t = jdbc.get();
        out.addAll((t == null ? localAssets.values().stream() : t.query("SELECT payload FROM audio_asset ORDER BY created_at DESC", (rs, i) -> read(rs.getString(1), Asset.class)).stream()).filter(a->!builtin.containsKey(a.id())).toList());
        return mediaObjects==null?out:out.stream().filter(a->a.builtin()||mediaObjects.available(a.key())).toList();
    }
    private Asset asset(String id) {
        var b = builtin.get(id); if (b != null) return b;
        var t = jdbc.get();
        if (t == null) { var a = localAssets.get(id); if (a == null || mediaObjects!=null&&!mediaObjects.available(a.key())) throw missing(); return a; }
        var rows = t.queryForList("SELECT payload FROM audio_asset WHERE asset_id=?", String.class, id);
        if (rows.isEmpty()) throw missing();var a=read(rows.getFirst(), Asset.class);if(mediaObjects!=null&&!mediaObjects.available(a.key()))throw missing();return a;
    }
    public Asset upload(String name, String kind, byte[] wav) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 30 || name.codePoints().anyMatch(Character::isISOControl)) throw AudioFiles.bad("名称须为 1～30 字");
        if (!Set.of("music", "effect").contains(kind)) throw AudioFiles.bad("请选择背景音乐或音效");
        var info = AudioFiles.inspect(wav);
        if (kind.equals("effect") && info.seconds() > 10) throw AudioFiles.bad("独立音效不得超过 10 秒");
        String id = UUID.randomUUID().toString(), key = "audio/" + id + "/" + info.sha256() + ".wav";
        objects.put(key, wav);
        if(mediaObjects!=null)mediaObjects.register(key,"audio",wav.length);
        var a = new Asset(id, name.strip(), kind, key, info, false, clock.millis());
        synchronized (this) {
            var t = jdbc.get();
            if (t == null) localAssets.put(id, a);
            else t.update("INSERT INTO audio_asset (asset_id,payload,published,created_at) VALUES (?,?,0,?)", id, write(a), a.createdAt());
        }
        return a;
    }
    public synchronized Draft save(Settings settings, int revision, long expectedVersion) {
        validate(settings);
        return transaction(() -> {
            stale(expectedVersion, revision);
            return storeDraft(new Draft(expectedVersion, revision + 1, settings));
        });
    }
    public Release publish(int revision, long expectedVersion) {
        Draft verified;
        synchronized (this) { stale(expectedVersion, revision); verified = draft(); validate(verified.settings()); }
        // Remote IO outside the database lock. Both draft and active version are checked again afterwards.
        for (String id : referenced(verified.settings())) bytes(id, true);
        synchronized (this) {
            return transaction(() -> {
                stale(expectedVersion, revision);
                return release(verified.settings(), revision);
            });
        }
    }
    public synchronized Release rollback(long version, long expectedVersion, int revision) {
        Settings s = load(version).settings(); validate(s);
        return transaction(() -> { stale(expectedVersion, revision); return release(s, revision); });
    }
    public synchronized List<Release> history() {
        var t = jdbc.get();
        var out = new ArrayList<>(t == null ? localHistory.values().stream().sorted(Comparator.comparingLong(Release::version).reversed()).limit(50).toList()
                : t.query("SELECT payload FROM audio_catalog ORDER BY version_id DESC LIMIT 50", (rs, i) -> read(rs.getString(1), Release.class)));
        out.add(load(0)); return out;
    }
    public synchronized Map<String,List<String>> managementReferences(){
        var out=new LinkedHashMap<String,List<String>>();
        java.util.function.BiConsumer<Settings,String> add=(settings,label)->{for(var id:referenced(settings)){var a=assets().stream().filter(v->v.id().equals(id)).findFirst().orElse(null);if(a!=null)out.computeIfAbsent(a.key(),k->new ArrayList<>()).add(label);}};
        add.accept(current().settings(),"当前音频版本 #"+current().version());add.accept(draft().settings(),"音频编辑草稿");
        var db=jdbc.get();var releases=db==null?List.copyOf(localHistory.values()):db.query("SELECT payload FROM audio_catalog",(r,i)->read(r.getString(1),Release.class));
        for(var release:releases)add.accept(release.settings(),"音频历史 #"+release.version());return out;
    }
    public byte[] bytes(String id, boolean draftAllowed) {
        Asset a; synchronized (this) { a = asset(id); if (!draftAllowed && !published(a)) throw missing(); }
        byte[] bytes = a.builtin() ? bundled(a.key()) : objects.get(a.key());
        if (!AudioFiles.inspect(bytes).sha256().equals(a.info().sha256())) throw AudioFiles.bad("音频完整性校验失败，请重新上传");
        return bytes;
    }
    private boolean published(Asset a) {
        if (a.builtin()) return true; // All shipped cues were explicitly approved on 2026-10-14.
        var t = jdbc.get();
        return t == null ? localPublished.contains(a.id()) : t.queryForList("SELECT published FROM audio_asset WHERE asset_id=?", Integer.class, a.id()).stream().anyMatch(v->v==1);
    }
    public synchronized Map<String, Object> admin() {
        return Map.of("current", current(), "draft", draft(), "assets", assets(), "history", history(), "storageReady", storageReady(), "persistent", persistent(),"candidateIds",List.of(),"candidateBindings",Map.copyOf(candidateBindings));
    }
    /** Management read permission exposes the active release, never unpublished resources or drafts. */
    public synchronized Map<String, Object> published() {
        Release release = current();
        return Map.of("current", release, "history", history(), "assets", referenced(release.settings()).stream().map(id -> {
            Asset asset = asset(id);
            return Map.of("id",asset.id(),"name",asset.name(),"kind",asset.kind(),"info",asset.info(),"builtin",asset.builtin());
        }).toList());
    }
    /** An immutable published release, with metadata only for resources referenced by that release. */
    public synchronized Map<String,Object> publishedVersion(long version) {
        Release release=load(version);
        return Map.of("release",release,"assets",referenced(release.settings()).stream().map(id->{
            Asset a=asset(id);
            return Map.of("id",a.id(),"name",a.name(),"kind",a.kind(),"info",a.info(),"builtin",a.builtin());
        }).toList());
    }
    public synchronized Map<String, Object> client() {
        Release r = current();
        return Map.of("version", r.version(), "settings", r.settings(), "assets", referenced(r.settings()).stream().map(id -> {
            Asset a = asset(id);
            return Map.of("id", a.id(), "name", a.name(), "url", "/api/audio/assets/" + a.id() + ".wav", "sha256", a.info().sha256(), "seconds", a.info().seconds());
        }).toList());
    }
    private void validate(Settings s) {
        if (s == null || s.musicVolume() < 0 || s.musicVolume() > 100 || s.effectsVolume() < 0 || s.effectsVolume() > 100 || s.fadeMs() < 0 || s.fadeMs() > 3000)
            throw AudioFiles.bad("音量须为 0～100，淡入淡出须为 0～3000 毫秒");
        if (!s.scenes().keySet().equals(SCENES) || !s.effects().keySet().equals(EFFECTS)) throw AudioFiles.bad("请完整配置所有场景和音效项目");
        for (var b : s.scenes().values()) {
            if (b == null || b.assetId() == null) throw AudioFiles.bad("场景配置无效");
            if (!b.assetId().isEmpty() && !asset(b.assetId()).kind().equals("music")) throw AudioFiles.bad("场景只能选择背景音乐");
        }
        for (String id : s.effects().values()) if (!id.isEmpty() && !asset(id).kind().equals("effect")) throw AudioFiles.bad("动作只能选择独立音效");
    }
    private static Set<String> referenced(Settings s) {
        Set<String> out = new LinkedHashSet<>(); s.scenes().values().forEach(b -> { if (!b.assetId().isEmpty()) out.add(b.assetId()); });
        s.effects().values().forEach(id -> { if (!id.isEmpty()) out.add(id); }); return out;
    }
    private Release release(Settings s, int revision) {
        var t = jdbc.get(); long version;
        if (t == null) version = next++;
        else {
            var keys = new GeneratedKeyHolder();
            t.update(c -> { var ps = c.prepareStatement("INSERT INTO audio_catalog (payload,created_at) VALUES (?,?)", new String[]{"version_id"});
                ps.setString(1, "{}"); ps.setLong(2, clock.millis()); return ps; }, keys);
            version = Objects.requireNonNull(keys.getKey()).longValue();
        }
        Release r = new Release(version, s, clock.millis());
        if (t == null) { localHistory.put(version, r); active = version; localPublished.addAll(referenced(s)); }
        else {
            t.update("UPDATE audio_catalog SET payload=? WHERE version_id=?", write(r), version);
            for (String id : referenced(s)) if (!builtin.containsKey(id)) t.update("UPDATE audio_asset SET published=1 WHERE asset_id=?", id);
            t.update("UPDATE audio_active SET version_id=? WHERE slot='default'", version);
        }
        storeDraft(new Draft(version, revision + 1, s)); return r;
    }
    private Draft storeDraft(Draft d) {
        var t = jdbc.get();
        if (t == null) localDraft = d;
        else t.update("UPDATE audio_active SET draft_revision=?,draft_payload=? WHERE slot='default'", d.revision(), write(d.settings()));
        return d;
    }
    private void stale(long version, int revision) {
        if (activeId() != version || draft().revision() != revision) throw new ClientException("STALE_AUDIO", "音频版本或草稿已变化，请刷新");
    }
    private <T> T transaction(Supplier<T> action) {
        var t = jdbc.get(); if (t == null) return action.get();
        return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(t.getDataSource()))).execute(status -> {
            t.queryForObject("SELECT version_id FROM audio_active WHERE slot='default' FOR UPDATE", Long.class); return action.get();
        });
    }
    private static byte[] bundled(String key) {
        try (var in = AudioCatalog.class.getClassLoader().getResourceAsStream(key)) {
            if (in == null) throw new IllegalStateException("Missing approved audio: " + key);
            return in.readAllBytes();
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private <T> T read(String value, Class<T> type) { try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalStateException("Invalid audio metadata", e); } }
    private static ClientException missing() { return new ClientException("NOT_FOUND", "音频资源或版本不存在"); }
}
