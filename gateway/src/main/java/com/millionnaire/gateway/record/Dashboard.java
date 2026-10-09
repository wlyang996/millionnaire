package com.millionnaire.gateway.record;

import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 运营数据看板（用户 2026-10-08）：最近若干天（北京时间按天）的开局数、参与人数、新用户、平均时长与人数，
 * 结束方式 / 地图 / 结束原因 / 人数分布，以及从对局日志统计的事件、幸运 / 不幸格、道具使用、拍卖、交易、小游戏、出局次数。
 * 只读数据库；未启用数据库时返回 dbEnabled=false。对局日志最多扫描最近 {@value #MAX_LOGS} 局（解压统计），其余指标不受限制。
 */
@Component
public class Dashboard {
    private static final Logger log = LoggerFactory.getLogger(Dashboard.class);
    static final int MAX_LOGS = 500;
    static final int MAX_DAYS = 90;
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ObjectProvider<JdbcTemplate> jdbc;
    private final Clock clock;

    public Dashboard(ObjectProvider<JdbcTemplate> jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Map<String, Object> build(int days) {
        int n = Math.max(1, Math.min(MAX_DAYS, days));
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        LocalDate first = today.minusDays(n - 1L);
        long from = first.atStartOfDay(ZONE).toInstant().toEpochMilli();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", n);
        out.put("from", first.toString());
        out.put("to", today.toString());
        JdbcTemplate t = jdbc.getIfAvailable();
        out.put("dbEnabled", t != null);
        if (t == null) {
            return out;
        }

        // 每天一行（含没有数据的日子）
        Map<LocalDate, long[]> daily = new TreeMap<>(); // games, minutesSum, newUsers
        Map<LocalDate, Set<Long>> players = new HashMap<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            daily.put(d, new long[3]);
            players.put(d, new HashSet<>());
        }
        Map<String, Integer> endModes = new TreeMap<>();
        Map<String, Integer> boards = new TreeMap<>();
        Map<String, Integer> reasons = new TreeMap<>();
        Map<String, Integer> sizes = new TreeMap<>();
        long[] totals = new long[3]; // games, minutesSum, playerSeats
        t.query("SELECT started_at, ended_at, end_mode, board_id, player_count, end_reason FROM game_record WHERE ended_at >= ?",
                rs -> {
                    long minutes = Math.max(0, (rs.getLong(2) - rs.getLong(1)) / 60_000);
                    long[] row = daily.get(day(rs.getLong(2)));
                    if (row != null) {
                        row[0]++;
                        row[1] += minutes;
                    }
                    totals[0]++;
                    totals[1] += minutes;
                    totals[2] += rs.getInt(5);
                    endModes.merge(rs.getString(3), 1, Integer::sum);
                    boards.merge(rs.getString(4), 1, Integer::sum);
                    reasons.merge(rs.getString(6), 1, Integer::sum);
                    sizes.merge(rs.getInt(5) + "人", 1, Integer::sum);
                }, from);
        Set<Long> allPlayers = new HashSet<>();
        t.query("SELECT ended_at, user_id FROM game_record_player WHERE ended_at >= ?", rs -> {
            Set<Long> s = players.get(day(rs.getLong(1)));
            if (s != null) {
                s.add(rs.getLong(2));
            }
            allPlayers.add(rs.getLong(2));
        }, from);
        long[] newUsers = new long[1];
        t.query("SELECT created_at FROM app_user WHERE created_at >= ? AND deleted_at IS NULL", rs -> {
            long[] row = daily.get(day(rs.getLong(1)));
            if (row != null) {
                row[2]++;
            }
            newUsers[0]++;
        }, from);

        List<Map<String, Object>> rows = new ArrayList<>();
        daily.forEach((d, v) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("date", d.toString());
            r.put("games", v[0]);
            r.put("players", players.get(d).size());
            r.put("newUsers", v[2]);
            r.put("avgMinutes", v[0] == 0 ? null : Math.round(v[1] * 10.0 / v[0]) / 10.0);
            rows.add(r);
        });
        Map<String, Object> sum = new LinkedHashMap<>();
        sum.put("games", totals[0]);
        sum.put("players", allPlayers.size());
        sum.put("newUsers", newUsers[0]);
        sum.put("avgMinutes", totals[0] == 0 ? null : Math.round(totals[1] * 10.0 / totals[0]) / 10.0);
        sum.put("avgPlayers", totals[0] == 0 ? null : Math.round(totals[2] * 10.0 / totals[0]) / 10.0);
        out.put("totals", sum);
        out.put("daily", rows);
        out.put("endModes", endModes);
        out.put("boards", boards);
        out.put("reasons", reasons);
        out.put("playerCounts", sizes);
        out.put("events", events(t, from));
        return out;
    }

    /** 最近的对局日志里各类事件的次数。 */
    private Map<String, Object> events(JdbcTemplate t, long from) {
        Map<String, Integer> drawn = new TreeMap<>();
        Map<String, Integer> lucky = new TreeMap<>();
        Map<String, Integer> cards = new TreeMap<>();
        Map<String, Long> misc = new LinkedHashMap<>();
        for (String k : List.of("rentPaid", "rentAmount", "propertiesBought", "upgrades", "auctionsSold", "auctionsPassed",
                "tradesDone", "minigames", "jailed", "bankrupt", "surrendered")) {
            misc.put(k, 0L);
        }
        int[] scanned = new int[1];
        t.query("SELECT payload FROM game_log WHERE ended_at >= ? ORDER BY ended_at DESC LIMIT " + MAX_LOGS, rs -> {
            scanned[0]++;
            try {
                byte[] plain = new GZIPInputStream(new ByteArrayInputStream(rs.getBytes(1))).readAllBytes();
                for (Event e : EventLogs.decode(new String(plain, StandardCharsets.UTF_8))) {
                    count(e, drawn, lucky, cards, misc);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("dashboard: cannot read a game log ({})", e.toString());
            }
        }, from);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("logsScanned", scanned[0]);
        out.put("drawnEvents", drawn);
        out.put("luckyEvents", lucky);
        out.put("cardsUsed", cards);
        out.putAll(misc);
        return out;
    }

    private static void count(Event e, Map<String, Integer> drawn, Map<String, Integer> lucky, Map<String, Integer> cards,
                              Map<String, Long> misc) {
        switch (e) {
            case GameEvent.EventDrawn x -> drawn.merge(x.kind().name(), 1, Integer::sum);
            case GameEvent.FixedEventTriggered x -> lucky.merge(x.kind().name(), 1, Integer::sum);
            case GameEvent.CardUsed x -> {
                if (x.active()) {
                    cards.merge(x.card().name(), 1, Integer::sum);
                }
            }
            case GameEvent.RentPaid x -> {
                misc.merge("rentPaid", 1L, Long::sum);
                misc.merge("rentAmount", x.amount(), Long::sum);
            }
            case GameEvent.PropertyBought x -> misc.merge("propertiesBought", 1L, Long::sum);
            case GameEvent.PropertyUpgraded x -> misc.merge("upgrades", 1L, Long::sum);
            case GameEvent.AuctionSettled x -> misc.merge("auctionsSold", 1L, Long::sum);
            case GameEvent.AuctionPassed x -> misc.merge("auctionsPassed", 1L, Long::sum);
            case GameEvent.TradeCompleted x -> misc.merge("tradesDone", 1L, Long::sum);
            case GameEvent.MinigameEnded x -> misc.merge("minigames", 1L, Long::sum);
            case GameEvent.PlayerJailed x -> misc.merge("jailed", 1L, Long::sum);
            case GameEvent.PlayerEliminated x -> misc.merge(
                    x.life() == com.millionnaire.engine.core.state.LifeState.SURRENDERED ? "surrendered" : "bankrupt", 1L, Long::sum);
            default -> {
            }
        }
    }

    private static LocalDate day(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZONE).toLocalDate();
    }
}
