package com.millionnaire.gateway.record;

import com.millionnaire.engine.EngineVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 战绩（表 game_record + game_record_player，见 V1__lean_baseline.sql 第 3 节）。
 * <ul>
 *   <li>局结束时由房间提交一份草稿，在单独的写线程里落库（不占房间锁）；同一 (room_id, game_no) 只写一次；</li>
 *   <li>表头与全部座位明细在同一事务里写入；</li>
 *   <li>未启用 db 配置时只在内存里保留每人最近 {@value #RECENT} 局（本地开发、测试）。</li>
 * </ul>
 */
@Component
public class GameRecords {
    private static final Logger log = LoggerFactory.getLogger(GameRecords.class);
    public static final int RECENT = 20;

    /** 一局的草稿：座位按开局顺序。 */
    public record Draft(long roomId, long gameNo, String reason, String endMode, Integer timeLimitMinutes, String boardId,
                        long initialCash, long startedAt, long endedAt, String configHash, List<Seat> seats) {
    }

    /** 一个座位的结算；rank 等为 null 表示中止局。 */
    public record Seat(int seatNo, long userId, Integer rank, Long netWorth, Long cash, String life) {
    }

    /** "我的最近 20 局"的一行。 */
    public record Row(long gameNo, String endMode, Integer timeLimitMinutes, String boardId, int playerCount,
                      long startedAt, long endedAt, String endReason, Integer rank, Long netWorth, Long cash, String life) {
    }

    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;
    private final Clock clock;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "game-records");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Deque<Row>> memory = new ConcurrentHashMap<>();

    @Autowired
    public GameRecords(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<PlatformTransactionManager> txm, Clock clock) {
        this.jdbc = jdbc::getIfAvailable;
        this.tx = () -> {
            PlatformTransactionManager m = txm.getIfAvailable();
            return m == null ? null : new TransactionTemplate(m);
        };
        this.clock = clock;
    }

    /** 只在内存里保存（不连数据库的单元测试用）。 */
    public static GameRecords inMemory(Clock clock) {
        return new GameRecords(clock);
    }

    private GameRecords(Clock clock) {
        this.jdbc = () -> null;
        this.tx = () -> null;
        this.clock = clock;
    }

    @PreDestroy
    void shutdown() {
        writer.shutdown();
    }

    /** 提交一局草稿（异步落库；失败只记日志，不影响房间）。 */
    public void submit(Draft d) {
        writer.execute(() -> {
            try {
                save(d);
            } catch (RuntimeException e) {
                log.error("cannot save game record room {} game {}", d.roomId(), d.gameNo(), e);
            }
        });
    }

    /** 同步保存（测试与写线程用）。 */
    void save(Draft d) {
        JdbcTemplate t = jdbc.get();
        TransactionTemplate txt = tx.get();
        if (t == null || txt == null) {
            for (Seat s : d.seats()) {
                Deque<Row> q = memory.computeIfAbsent(s.userId(), k -> new ArrayDeque<>());
                synchronized (q) {
                    q.addFirst(row(d, s));
                    while (q.size() > RECENT) {
                        q.removeLast();
                    }
                }
            }
            return;
        }
        long now = clock.millis();
        byte[] sha = sha256(canonical(d));
        try {
            txt.executeWithoutResult(status -> {
                t.update("INSERT INTO game_record (room_id, game_no, outcome, end_reason, end_mode, time_limit_min, board_id,"
                                + " initial_cash, player_count, started_at, ended_at, engine_version, config_hash, draft_sha256,"
                                + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        d.roomId(), d.gameNo(), "FINISHED", d.reason(), d.endMode(), d.timeLimitMinutes(), d.boardId(),
                        d.initialCash(), d.seats().size(), d.startedAt(), d.endedAt(), EngineVersion.VALUE, d.configHash(),
                        sha, now);
                Long recordId = t.queryForObject("SELECT record_id FROM game_record WHERE room_id = ? AND game_no = ?",
                        Long.class, d.roomId(), d.gameNo());
                for (Seat s : d.seats()) {
                    // 已注销 / 不存在的用户不写明细（外键也会拒绝）
                    Integer live = t.queryForObject("SELECT COUNT(*) FROM app_user WHERE user_id = ? AND live_mark = 1",
                            Integer.class, s.userId());
                    if (live == null || live == 0) {
                        continue;
                    }
                    t.update("INSERT INTO game_record_player (record_id, seat_no, user_id, ended_at, finish_rank, final_net_worth,"
                                    + " final_cash, life_state, user_live) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
                            recordId, s.seatNo(), s.userId(), d.endedAt(), s.rank(), s.netWorth(), s.cash(), s.life());
                }
                t.update("UPDATE room SET max_saved_game_no = GREATEST(max_saved_game_no, ?), updated_at = ? WHERE room_id = ?",
                        d.gameNo(), now, d.roomId());
            });
        } catch (DuplicateKeyException e) {
            log.info("game record room {} game {} already saved", d.roomId(), d.gameNo());
        }
    }

    /** 某人最近 {@value #RECENT} 局，新的在前。 */
    public List<Row> recent(long userId) {
        JdbcTemplate t = jdbc.get();
        if (t == null) {
            Deque<Row> q = memory.get(userId);
            if (q == null) {
                return List.of();
            }
            synchronized (q) {
                return new ArrayList<>(q);
            }
        }
        return t.query("SELECT r.game_no, r.end_mode, r.time_limit_min, r.board_id, r.player_count, r.started_at, r.ended_at,"
                        + " r.end_reason, p.finish_rank, p.final_net_worth, p.final_cash, p.life_state"
                        + " FROM game_record_player p JOIN game_record r ON r.record_id = p.record_id"
                        + " WHERE p.user_id = ? AND p.user_live = 1 ORDER BY p.ended_at DESC, p.record_id DESC LIMIT " + RECENT,
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2), (Integer) rs.getObject(3, Integer.class), rs.getString(4),
                        rs.getInt(5), rs.getLong(6), rs.getLong(7), rs.getString(8), (Integer) rs.getObject(9, Integer.class),
                        (Long) rs.getObject(10, Long.class), (Long) rs.getObject(11, Long.class), rs.getString(12)),
                userId);
    }

    private static Row row(Draft d, Seat s) {
        return new Row(d.gameNo(), d.endMode(), d.timeLimitMinutes(), d.boardId(), d.seats().size(), d.startedAt(),
                d.endedAt(), d.reason(), s.rank(), s.netWorth(), s.cash(), s.life());
    }

    /** 草稿的规范文本（全部座位）：重复提交时用其 SHA-256 判断是否同一份。 */
    private static String canonical(Draft d) {
        StringBuilder b = new StringBuilder();
        b.append(d.roomId()).append('|').append(d.gameNo()).append('|').append(d.reason()).append('|').append(d.endMode())
                .append('|').append(d.timeLimitMinutes()).append('|').append(d.boardId()).append('|').append(d.initialCash())
                .append('|').append(d.startedAt()).append('|').append(d.endedAt()).append('|').append(d.configHash());
        for (Seat s : d.seats()) {
            b.append('\n').append(s.seatNo()).append('|').append(s.userId()).append('|').append(s.rank()).append('|')
                    .append(s.netWorth()).append('|').append(s.cash()).append('|').append(s.life());
        }
        return b.toString();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
