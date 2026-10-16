package com.millionnaire.gateway.auth;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 用户：启用 db 配置时写 app_user，否则只在内存（本地开发、测试）。 */
@Component
public class UserStore {
    public Map<String,Object> adminProfile(long id){
        var db=jdbc.getIfAvailable();if(db==null){var u=cache.get(id);if(u==null)throw new com.millionnaire.gateway.admin.AdminFault(404,"NOT_FOUND","玩家不存在");return Map.of("userId",u.id(),"nickname",u.nickname(),"avatarId",u.avatarId(),"status","ACTIVE");}
        var found=db.query("SELECT user_id,nickname,avatar_id,status,created_at,last_login_at FROM app_user WHERE user_id=?",(r,i)->Map.<String,Object>of("userId",r.getLong("user_id"),"nickname",r.getString("nickname"),"avatarId",r.getInt("avatar_id"),"status",r.getString("status"),"createdAt",r.getLong("created_at"),"lastLoginAt",r.getLong("last_login_at")),id);
        if(found.isEmpty())throw new com.millionnaire.gateway.admin.AdminFault(404,"NOT_FOUND","玩家不存在");return found.getFirst();
    }
    /** Management projection deliberately excludes WeChat identities and credentials. */
    public Map<String,Object> adminPage(int page,int size,String q){
        if(page<1||page>100000||size<1||size>100||q.length()>100)throw new com.millionnaire.gateway.admin.AdminFault(400,"BAD_REQUEST","玩家筛选参数不正确");
        var t=jdbc.getIfAvailable();
        if(t==null){var rows=cache.values().stream().filter(u->u.nickname().contains(q)||Long.toString(u.id()).equals(q)).sorted(java.util.Comparator.comparingLong(User::id)).toList();return Map.of("persistent",false,"items",rows.stream().skip((long)(page-1)*size).limit(size).map(u->Map.of("userId",u.id(),"nickname",u.nickname(),"avatarId",u.avatarId(),"status","ACTIVE")).toList(),"total",rows.size());}
        String where=" WHERE (nickname LIKE ? ESCAPE '!' OR user_id=?)";
        long exactId=-1;try{exactId=Long.parseLong(q);}catch(NumberFormatException ignored){}
        String like="%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        long total=t.queryForObject("SELECT COUNT(*) FROM app_user"+where,Long.class,like,exactId);
        var rows=t.query("SELECT user_id,nickname,avatar_id,status,created_at,last_login_at FROM app_user"+where+" ORDER BY created_at DESC,user_id DESC LIMIT ? OFFSET ?",(r,i)->Map.of("userId",r.getLong("user_id"),"nickname",r.getString("nickname"),"avatarId",r.getInt("avatar_id"),"status",r.getString("status"),"createdAt",r.getLong("created_at"),"lastLoginAt",r.getLong("last_login_at")),like,exactId,size,(page-1)*size);
        return Map.of("persistent",true,"items",rows,"total",total);
    }
    /** avatarId：app_user.avatar_id，0 = 没选（客户端按玩家 ID 取默认头像），1～8 = 头像序号 + 1。 */
    public record User(long id, String nickname, int avatarId) {
        public User(long id, String nickname) {
            this(id, nickname, 0);
        }

        /** 稳定人物 ID（0～998，8 为机器人）；没选时为 -1。 */
        public int avatar() {
            return avatarId > 0 ? avatarId - 1 : -1;
        }

        /** 引擎里的玩家 ID。 */
        public String playerId() {
            return Long.toString(id);
        }
    }

    /** 没有目录服务的旧测试实例使用八位内置人物。 */
    public static final int AVATARS = 8;

    private final ObjectProvider<JdbcTemplate> jdbc;
    private final Clock clock;
    private final com.millionnaire.gateway.characters.CharacterCatalog characters;
    private final Map<Long, User> cache = new ConcurrentHashMap<>();
    /** 没连库时的微信身份表。 */
    private final Map<String, Long> identities = new ConcurrentHashMap<>();

    public UserStore(ObjectProvider<JdbcTemplate> jdbc, Clock clock) {
        this(jdbc, clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public UserStore(ObjectProvider<JdbcTemplate> jdbc, Clock clock, com.millionnaire.gateway.characters.CharacterCatalog characters) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.characters = characters;
    }

    private boolean validAvatar(int avatar) {
        if (avatar < 0) return false;
        if (characters == null) return avatar < AVATARS;
        if (!characters.selectable(avatar)) throw new com.millionnaire.gateway.room.ClientException("INVALID_AVATAR", "该人物已停用或尚未发布，请重新选择");
        return true;
    }

    public User create(String nickname) {
        return create(nickname, -1);
    }

    /** @param avatar 已发布且启用的人物 ID；负数表示没选。 */
    public User create(String nickname, int avatar) {
        JdbcTemplate t = jdbc.getIfAvailable();
        long now = clock.millis();
        int avatarId = validAvatar(avatar) ? avatar + 1 : 0;
        for (int attempt = 1; ; attempt++) {
            User u = new User(Ids.nextId(), nickname, avatarId);
            if (t == null) {
                if (cache.putIfAbsent(u.id(), u) == null) {
                    return u;
                }
                continue;
            }
            try {
                t.update("INSERT INTO app_user (user_id, nickname, avatar_id, status, created_at, updated_at, last_login_at)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)", u.id(), nickname, avatarId, now, now, now);
                cache.put(u.id(), u);
                return u;
            } catch (DuplicateKeyException e) {
                if (attempt >= 3) {
                    throw e;
                }
            }
        }
    }

    /** 微信身份：appId + openid → 用户（没连库时在内存）。 */
    public Optional<User> findByOpenid(String appId, String openid) {
        JdbcTemplate t = jdbc.getIfAvailable();
        if (t == null) {
            Long id = identities.get(appId + '\n' + openid);
            return id == null ? Optional.empty() : Optional.ofNullable(cache.get(id));
        }
        List<Long> ids = t.query("SELECT user_id FROM wx_identity WHERE app_id = ? AND open_id = ?",
                (rs, i) -> rs.getLong(1), bytes(appId), bytes(openid));
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        long now = clock.millis();
        t.update("UPDATE wx_identity SET last_login_at = ? WHERE app_id = ? AND open_id = ?", now, bytes(appId), bytes(openid));
        t.update("UPDATE app_user SET last_login_at = ? WHERE user_id = ?", now, ids.get(0));
        cache.remove(ids.get(0));
        return find(ids.get(0));
    }

    /** 首次微信登录：建用户并绑定 openid；并发重复登录时返回先建好的那个。 */
    public User createWithOpenid(String appId, String openid, String nickname, int avatar) {
        JdbcTemplate t = jdbc.getIfAvailable();
        User u = create(nickname, avatar);
        if (t == null) {
            Long prev = identities.putIfAbsent(appId + '\n' + openid, u.id());
            if (prev != null) {
                cache.remove(u.id());
                return cache.get(prev);
            }
            return u;
        }
        long now = clock.millis();
        try {
            t.update("INSERT INTO wx_identity (app_id, open_id, user_id, created_at, last_login_at) VALUES (?, ?, ?, ?, ?)",
                    bytes(appId), bytes(openid), u.id(), now, now);
            return u;
        } catch (DuplicateKeyException e) {
            // 同一微信号并发首次登录：删掉刚建的空用户（还没有任何引用），用先绑定的
            t.update("DELETE FROM app_user WHERE user_id = ?", u.id());
            cache.remove(u.id());
            return findByOpenid(appId, openid).orElseThrow(() -> e);
        }
    }

    /** 改昵称 / 人物（负数保留原人物，非负 ID 须已发布且启用）。 */
    public User updateProfile(User u, String nickname, int avatar) {
        int avatarId = validAvatar(avatar) ? avatar + 1 : u.avatarId();
        User next = new User(u.id(), nickname, avatarId);
        JdbcTemplate t = jdbc.getIfAvailable();
        if (t != null) {
            t.update("UPDATE app_user SET nickname = ?, avatar_id = ?, updated_at = ? WHERE user_id = ?",
                    nickname, avatarId, clock.millis(), u.id());
        }
        cache.put(u.id(), next);
        return next;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Only trusted persisted identities can be used for provider content checks. */
    public Optional<String> openid(long userId, String appId) {
        JdbcTemplate t=jdbc.getIfAvailable();
        if(t==null) return identities.entrySet().stream().filter(e->e.getValue()==userId && e.getKey().startsWith(appId+'\n')).map(e->e.getKey().substring(appId.length()+1)).findFirst();
        return t.query("SELECT open_id FROM wx_identity WHERE user_id=? AND app_id=?",(rs,i)->new String(rs.getBytes(1),java.nio.charset.StandardCharsets.UTF_8),userId,bytes(appId)).stream().findFirst();
    }

    public Optional<User> find(long id) {
        User cached = cache.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        JdbcTemplate t = jdbc.getIfAvailable();
        if (t == null) {
            return Optional.empty();
        }
        List<User> rows = t.query("SELECT user_id, nickname, avatar_id FROM app_user WHERE user_id = ? AND status = 'ACTIVE'",
                (rs, i) -> new User(rs.getLong(1), rs.getString(2), rs.getInt(3)), id);
        rows.forEach(u -> cache.put(u.id(), u));
        return rows.stream().findFirst();
    }
}
