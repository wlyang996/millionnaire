package com.millionnaire.gateway.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.engine.core.command.RoomCommand.SetReady;
import com.millionnaire.engine.core.command.SessionCommand.StartGame;
import com.millionnaire.engine.core.state.SessionView;
import com.millionnaire.gateway.auth.UserStore.User;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** 定时唤醒：回合窗口到期时由 Tick 推进（投骰超时自动投），不依赖任何客户端消息。 */
class LiveRoomTimerTest {
    static final class MutableClock extends Clock {
        volatile long now;

        MutableClock(long now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }
    }

    @Test
    void rollDeadlineAutoRollsOnWakeUp() {
        MutableClock clock = new MutableClock(System.currentTimeMillis());
        List<String> sent = new CopyOnWriteArrayList<>();
        RoomStore store = new RoomStore(new StaticListableBeanFactory().getBeanProvider(JdbcTemplate.class), clock);
        RoomService rooms = new RoomService(store, (player, json) -> sent.add(json), new Wire(new ObjectMapper()), clock);
        try {
            LiveRoom room = rooms.create(new User(1, "阿杰"), "c", null).room();
            assertThat(rooms.join(new User(2, "糖糖"), room.codeNumber(), "j").ok()).isTrue();
            assertThat(room.submitClient("1", "r1", new SetReady("1", true)).ok()).isTrue();
            assertThat(room.submitClient("2", "r2", new SetReady("2", true)).ok()).isTrue();
            assertThat(room.submitClient("1", "s", new StartGame("1")).ok()).isTrue();

            String current = room.view("1").game().currentPlayer();
            long due = room.scheduledWakeAt();
            assertThat(due).isGreaterThan(clock.millis());
            sent.clear();

            clock.now = due; // 投骰窗口截止
            rooms.wake(room, due);

            assertThat(sent).anyMatch(m -> m.contains("DiceRolled"));
            SessionView v = room.view("1");
            int position = v.game().players().stream().filter(p -> p.playerId().equals(current)).findFirst()
                    .orElseThrow().position();
            assertThat(position).isBetween(1, 6);
            assertThat(room.scheduledWakeAt()).isGreaterThan(due); // 已安排下一次唤醒
            // 过期的唤醒（已被取代）什么也不做
            long version = room.view("1").game().turnNo();
            rooms.wake(room, due);
            assertThat(room.view("1").game().turnNo()).isEqualTo(version);
        } finally {
            rooms.shutdown();
        }
    }

    /** 没人操作：每人连续两次投骰超时后被判定挂机（暂离）；全员挂机后对局在回合交界结束（ALL_AWAY）。 */
    @Test
    void playersWhoKeepMissingTheirRollAreMarkedAwayAndAllAwayEndsTheGame() {
        MutableClock clock = new MutableClock(System.currentTimeMillis());
        List<String> sent = new CopyOnWriteArrayList<>();
        RoomStore store = new RoomStore(new StaticListableBeanFactory().getBeanProvider(JdbcTemplate.class), clock);
        RoomService rooms = new RoomService(store, (player, json) -> sent.add(json), new Wire(new ObjectMapper()), clock);
        try {
            LiveRoom room = rooms.create(new User(1, "阿杰"), "c", null).room();
            assertThat(rooms.join(new User(2, "糖糖"), room.codeNumber(), "j").ok()).isTrue();
            assertThat(room.submitClient("1", "r1", new SetReady("1", true)).ok()).isTrue();
            assertThat(room.submitClient("2", "r2", new SetReady("2", true)).ok()).isTrue();
            assertThat(room.submitClient("1", "s", new StartGame("1")).ok()).isTrue();
            sent.clear();

            for (int i = 0; i < 200 && room.gameNo().isPresent(); i++) {
                long due = room.scheduledWakeAt();
                if (due == Long.MIN_VALUE) {
                    break;
                }
                clock.now = Math.max(clock.now, due);
                rooms.wake(room, due);
            }

            assertThat(room.gameNo()).isEmpty();
            assertThat(room.view("1").lastResult().reason()).isEqualTo("ALL_AWAY");
            assertThat(sent).anyMatch(m -> m.contains("ControlChanged") && m.contains("\"playerId\":\"1\"") && m.contains("AWAY"));
            assertThat(sent).anyMatch(m -> m.contains("ControlChanged") && m.contains("\"playerId\":\"2\"") && m.contains("AWAY"));
        } finally {
            rooms.shutdown();
        }
    }

    /** 连接判定：静默 15 秒疑似断线、30 秒确认掉线、再次有消息即重连；全员掉线 120 秒后中止本局。 */
    @Test
    void silentPlayersBecomeSuspectThenOfflineAndAllOfflineAbortsTheGame() {
        MutableClock clock = new MutableClock(System.currentTimeMillis());
        List<String> sent = new CopyOnWriteArrayList<>();
        RoomStore store = new RoomStore(new StaticListableBeanFactory().getBeanProvider(JdbcTemplate.class), clock);
        RoomService rooms = new RoomService(store, (player, json) -> sent.add(json), new Wire(new ObjectMapper()), clock);
        try {
            LiveRoom room = rooms.create(new User(1, "阿杰"), "c", null).room();
            assertThat(rooms.join(new User(2, "糖糖"), room.codeNumber(), "j").ok()).isTrue();
            assertThat(room.submitClient("1", "r1", new SetReady("1", true)).ok()).isTrue();
            assertThat(room.submitClient("2", "r2", new SetReady("2", true)).ok()).isTrue();
            assertThat(room.submitClient("1", "s", new StartGame("1")).ok()).isTrue();
            Presence presence = rooms.presence();
            long t0 = clock.now;
            presence.touch("1", t0);
            presence.touch("2", t0);

            // 玩家 1 一直有心跳；玩家 2 静默
            clock.now = t0 + 14_000;
            presence.touch("1", clock.now);
            rooms.checkConnections();
            assertThat(conn(room, "2")).isEqualTo("ONLINE");
            clock.now = t0 + 15_000;
            rooms.checkConnections();
            assertThat(conn(room, "2")).isEqualTo("SUSPECT");
            clock.now = t0 + 29_000;
            presence.touch("1", clock.now);
            rooms.checkConnections();
            assertThat(conn(room, "2")).isEqualTo("SUSPECT");
            clock.now = t0 + 30_000;
            rooms.checkConnections();
            assertThat(conn(room, "2")).isEqualTo("OFFLINE");
            assertThat(conn(room, "1")).isEqualTo("ONLINE");

            // 玩家 2 重新发来消息：重连
            clock.now = t0 + 31_000;
            presence.touch("2", clock.now);
            rooms.checkConnections();
            assertThat(conn(room, "2")).isEqualTo("ONLINE");

            // 两人都静默：先后疑似、掉线；全员掉线满 120 秒后中止
            long quiet = clock.now;
            for (long t = quiet + 1_000; t <= quiet + 30_000; t += 1_000) {
                clock.now = t;
                rooms.checkConnections();
            }
            assertThat(conn(room, "1")).isEqualTo("OFFLINE");
            assertThat(conn(room, "2")).isEqualTo("OFFLINE");
            clock.now = quiet + 30_000 + 119_000;
            rooms.checkConnections();
            assertThat(room.gameNo()).isPresent();
            clock.now = quiet + 30_000 + 120_000;
            rooms.checkConnections();
            assertThat(room.gameNo()).isEmpty();
            assertThat(sent).anyMatch(m -> m.contains("GameAborted") && m.contains("ALL_OFFLINE"));
        } finally {
            rooms.shutdown();
        }
    }

    private static String conn(LiveRoom room, String player) {
        return room.view(player).game().players().stream().filter(p -> p.playerId().equals(player)).findFirst()
                .orElseThrow().conn().name();
    }

    /**
     * 虎口拔牙走完整条联机链路：落到游戏区后推送里带 minigame 视图与 MinigameStarted（不含危险牙），
     * 选牙经 Wire 解析 GAME/PickTooth，结束时推送 MinigameEnded 并发奖励。
     */
    @Test
    void minigameRunsOverTheWireProtocol() {
        MutableClock clock = new MutableClock(System.currentTimeMillis());
        List<String> sent = new CopyOnWriteArrayList<>();
        RoomStore store = new RoomStore(new StaticListableBeanFactory().getBeanProvider(JdbcTemplate.class), clock);
        Wire wire = new Wire(new ObjectMapper());
        RoomService rooms = new RoomService(store, (player, json) -> sent.add(json), wire, clock);
        try {
            LiveRoom room = rooms.create(new User(1, "阿杰"), "c", null).room();
            assertThat(rooms.join(new User(2, "糖糖"), room.codeNumber(), "j").ok()).isTrue();
            assertThat(room.submitClient("1", "r1", new SetReady("1", true)).ok()).isTrue();
            assertThat(room.submitClient("2", "r2", new SetReady("2", true)).ok()).isTrue();
            assertThat(room.submitClient("1", "s", new StartGame("1")).ok()).isTrue();
            int req = 0;
            // 手动投骰（避免连续超时被判挂机），其余窗口等到期自动处理，直到有人落在游戏区
            for (int i = 0; i < 2000 && room.view("1").game() != null && room.view("1").game().minigame() == null; i++) {
                var g = room.view("1").game();
                if (g.windows().isEmpty()) {
                    assertThat(g.progress().notices()).isNotEmpty();
                    clock.now = room.scheduledWakeAt();
                    rooms.wake(room, clock.now);
                    continue;
                }
                var top = g.windows().get(g.windows().size() - 1);
                if (top.kind() == com.millionnaire.engine.core.state.FlowKind.TURN
                        && (g.stage() == com.millionnaire.engine.core.state.TurnStage.PRE_ROLL
                            || g.stage() == com.millionnaire.engine.core.state.TurnStage.JAIL_DECISION)) {
                    clock.now = Math.max(clock.now + 1, top.opensAt());
                    room.submitClient(top.owner(), "roll" + (++req),
                            wire.gameCommand("RollDice", wire.object().put("windowId", top.windowId()), top.owner()));
                } else if (top.kind() == com.millionnaire.engine.core.state.FlowKind.TURN
                        && g.stage() == com.millionnaire.engine.core.state.TurnStage.LANDING) {
                    // 落点决策直接处理（不等 15 秒超时，免得全局时钟先到）：放弃购买 / 抽事件卡 / 不用免租卡等
                    var landing = g.landing();
                    String command = landing == null ? null : switch (landing.step()) {
                        case BUY -> "DeclinePurchase";
                        case UPGRADE -> "SkipUpgrade";
                        case BANK -> "FinishBank";
                        case EVENT -> "DrawEventCard";
                        case RESPONSE -> "RespondCard";
                        default -> null;
                    };
                    clock.now = Math.max(clock.now + 1, top.opensAt());
                    if (command == null) {
                        long due = room.scheduledWakeAt();
                        clock.now = Math.max(clock.now, due);
                        rooms.wake(room, due);
                    } else {
                        room.submitClient(top.owner(), "act" + (++req),
                                wire.gameCommand(command, wire.object().put("windowId", top.windowId()), top.owner()));
                    }
                } else {
                    long due = room.scheduledWakeAt();
                    clock.now = Math.max(clock.now, due);
                    rooms.wake(room, due);
                }
            }
            assertThat(room.view("1").game()).as("game still running: " + sent.stream().filter(j -> j.contains("GameEnded")).findFirst().orElse("")).isNotNull();
            var m = room.view("1").game().minigame();
            assertThat(m).as("someone reached the game zone").isNotNull();
            assertThat(m.teeth()).isEqualTo(4);
            assertThat(sent).anyMatch(j -> j.contains("\"MinigameStarted\"") && j.contains("\"minigame\":{"));
            assertThat(sent).noneMatch(j -> j.contains("danger"));
            long before1 = room.view("1").game().players().stream().filter(p -> p.playerId().equals("1")).findFirst().orElseThrow().cash();
            long before2 = room.view("1").game().players().stream().filter(p -> p.playerId().equals("2")).findFirst().orElseThrow().cash();
            while (room.view("1").game() != null && room.view("1").game().minigame() != null) {
                var now = room.view("1").game().minigame();
                var w = room.view("1").game().windows().stream().filter(x -> x.windowId() == now.windowId()).findFirst().orElseThrow();
                clock.now = Math.max(clock.now + 1, w.opensAt());
                int tooth = java.util.stream.IntStream.range(0, now.teeth()).filter(t -> !now.picks().contains(t)).findFirst().orElseThrow();
                var reply = room.submitClient(now.picker(), "pick" + (++req), wire.gameCommand("PickTooth",
                        wire.object().put("windowId", now.windowId()).put("tooth", tooth), now.picker()));
                assertThat(reply.ok()).isTrue();
            }
            assertThat(sent).anyMatch(j -> j.contains("\"MinigameEnded\"") && j.contains("\"danger\""));
            var after = room.view("1").game().players();
            long after1 = after.stream().filter(p -> p.playerId().equals("1")).findFirst().orElseThrow().cash();
            long after2 = after.stream().filter(p -> p.playerId().equals("2")).findFirst().orElseThrow().cash();
            assertThat((after1 - before1) + (after2 - before2)).as("exactly one winner gets 500").isEqualTo(500);
        } finally {
            rooms.shutdown();
        }
    }
}
