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
    /** avatarId：app_user.avatar_id，0 = 没选（客户端按玩家 ID 取默认头像），1～8 = 头像序号 + 1。 */
    public record User(long id, String nickname, int avatarId) {
        public User(long id, String nickname) {
            this(id, nickname, 0);
        }

        /** 头像序号 0～7；没选时为 -1。 */
        public int avatar() {
            return avatarId > 0 ? avatarId - 1 : -1;
        }

        /** 引擎里的玩家 ID。 */
        public String playerId() {
            return Long.toString(id);
        }
    }

    /** 客户端可选的头像数。 */
    public static final int AVATARS = 8;

    private final ObjectProvider<JdbcTemplate> jdbc;
    private final Clock clock;
    private final Map<Long, User> cache = new ConcurrentHashMap<>();

    public UserStore(ObjectProvider<JdbcTemplate> jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public User create(String nickname) {
        return create(nickname, -1);
    }

    /** @param avatar 头像序号 0～7；其他值视为没选。 */
    public User create(String nickname, int avatar) {
        JdbcTemplate t = jdbc.getIfAvailable();
        long now = clock.millis();
        int avatarId = avatar >= 0 && avatar < AVATARS ? avatar + 1 : 0;
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
