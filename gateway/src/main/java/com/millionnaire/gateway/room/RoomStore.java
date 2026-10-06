package com.millionnaire.gateway.room;

import com.millionnaire.gateway.auth.Ids;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 房间号分配与房间行（表 room，见 V1__lean_baseline.sql 第 2 节，测试版 v3）。未启用 db 配置时只在内存里保证号码唯一。
 * <ul>
 *   <li>held_code 唯一：占用中或冷却中的号码不会重复分配；关闭满 {@link #COOLDOWN_MS} 后由新建房释放；</li>
 *   <li>测试版不记录建房进程、不做启动清扫：重启后上一进程留下的 OPEN 行是孤儿，按号加入一律以内存里的房间为准；</li>
 *   <li>room_id 随机生成，并核验 game_record / game_log 中没有同号（房间行清理后战绩仍保留，设计 §1）。</li>
 * </ul>
 */
@Component
public class RoomStore {
    static final long COOLDOWN_MS = 10 * 60 * 1000L;

    /** 新开的房间行：房间 ID 与六位房间号。 */
    public record Opened(long roomId, int code) {
    }

    private final ObjectProvider<JdbcTemplate> jdbc;
    private final Clock clock;
    private final Map<Integer, Long> memoryCodes = new ConcurrentHashMap<>();

    public RoomStore(ObjectProvider<JdbcTemplate> jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 分配房间 ID 与六位房间号，并写入 OPEN 房间行。 */
    public Opened open(long createdBy, byte[] requestKey) {
        JdbcTemplate t = jdbc.getIfAvailable();
        long now = clock.millis();
        for (int attempt = 0; attempt < 50; attempt++) {
            long roomId = Ids.nextId();
            int code = Ids.roomCode();
            if (t == null) {
                if (memoryCodes.putIfAbsent(code, roomId) == null) {
                    return new Opened(roomId, code);
                }
                continue;
            }
            Integer used = t.queryForObject("SELECT (SELECT COUNT(*) FROM game_record WHERE room_id = ?)"
                    + " + (SELECT COUNT(*) FROM game_log WHERE room_id = ?)", Integer.class, roomId, roomId);
            if (used != null && used > 0) {
                continue;
            }
            t.update("UPDATE room SET held_code = NULL, updated_at = ? WHERE held_code = ? AND status = 'CLOSED'"
                    + " AND closed_at < ?", now, code, now - COOLDOWN_MS);
            try {
                t.update("INSERT INTO room (room_id, room_code, held_code, created_by, create_request_id, status,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?)",
                        roomId, code, code, createdBy, requestKey, now, now);
                return new Opened(roomId, code);
            } catch (DuplicateKeyException e) {
                Integer reused = t.queryForObject("SELECT COUNT(*) FROM room WHERE created_by = ? AND create_request_id = ?",
                        Integer.class, createdBy, requestKey);
                if (reused != null && reused > 0) {
                    throw new ClientException("DUPLICATE_REQUEST", "requestId was already used to create a room");
                }
                // 号码或 ID 冲突：换一组再试
            }
        }
        throw new IllegalStateException("no free room code after 50 attempts");
    }

    public void close(long roomId, int code, String reason) {
        memoryCodes.remove(code, roomId);
        JdbcTemplate t = jdbc.getIfAvailable();
        if (t == null) {
            return;
        }
        long now = clock.millis();
        t.update("UPDATE room SET status = 'CLOSED', closed_at = ?, close_reason = ?, updated_at = ?"
                + " WHERE room_id = ? AND status = 'OPEN'", now, reason, now, roomId);
    }
}
