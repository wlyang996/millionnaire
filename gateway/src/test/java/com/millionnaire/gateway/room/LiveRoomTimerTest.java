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
}
