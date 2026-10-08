package com.millionnaire.gateway.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.engine.core.command.RoomCommand.SetReady;
import com.millionnaire.engine.core.command.SessionCommand.StartGame;
import com.millionnaire.gateway.auth.UserStore.User;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** 观战（用户 2026-10-08）：不在房间的人凭房间号观看进行中的对局，收推送、不能操作；加入别的房间或房间关闭后不再收。 */
class WatchTest {
    private record Sent(String to, String json) {
    }

    @Test
    void spectatorsFollowTheGameButCannotAct() {
        Clock clock = Clock.systemUTC();
        List<Sent> sent = new CopyOnWriteArrayList<>();
        RoomStore store = new RoomStore(new StaticListableBeanFactory().getBeanProvider(JdbcTemplate.class), clock);
        RoomService rooms = new RoomService(store, (p, json) -> sent.add(new Sent(p, json)), new Wire(new ObjectMapper()), clock);
        try {
            LiveRoom room = rooms.create(new User(1, "阿杰"), "c", null).room();
            assertThat(rooms.join(new User(2, "糖糖"), room.codeNumber(), "j").ok()).isTrue();
            User viewer = new User(3, "路人");
            assertThatThrownBy(() -> rooms.watch(viewer, room.codeNumber()))
                    .isInstanceOf(ClientException.class).hasMessageContaining("not started");
            assertThatThrownBy(() -> rooms.watch(new User(2, "糖糖"), room.codeNumber()))
                    .isInstanceOf(ClientException.class).hasMessageContaining("leave your room");
            assertThat(room.submitClient("1", "r1", new SetReady("1", true)).ok()).isTrue();
            assertThat(room.submitClient("2", "r2", new SetReady("2", true)).ok()).isTrue();
            assertThat(room.submitClient("1", "s", new StartGame("1")).ok()).isTrue();

            assertThat(rooms.watch(viewer, room.codeNumber())).isSameAs(room);
            assertThat(rooms.watchingOf("3")).contains(room);
            assertThat(rooms.roomOf("3")).isEmpty();
            assertThat(room.watcherCount()).isEqualTo(1);
            // 非玩家视角：能看到对局，没有手牌
            assertThat(room.view("3").game()).isNotNull();
            assertThat(room.view("3").game().myHand()).isEmpty();

            sent.clear();
            room.chat("1", "阿杰", "大家好");
            assertThat(sent).anyMatch(s -> s.to().equals("3") && s.json().contains("大家好"));
            assertThatThrownBy(() -> room.chat("3", "路人", "我也说一句")).isInstanceOf(ClientException.class);

            sent.clear();
            room.submitSystem(new com.millionnaire.engine.core.command.GameCommand.SetControl(
                    room.gameNo().getAsLong(), "1", com.millionnaire.engine.core.state.ControlMode.HOSTED));
            assertThat(sent).anyMatch(s -> s.to().equals("3") && s.json().contains("UPDATE"));

            rooms.unwatch("3");
            assertThat(room.watcherCount()).isZero();
            assertThat(rooms.watchingOf("3")).isEmpty();
            sent.clear();
            room.chat("2", "糖糖", "还在吗");
            assertThat(sent).noneMatch(s -> s.to().equals("3"));

            // 观战中去开自己的房间：自动退出观战
            rooms.watch(viewer, room.codeNumber());
            rooms.create(viewer, "c3", null);
            assertThat(rooms.watchingOf("3")).isEmpty();
            assertThat(room.watcherCount()).isZero();
        } finally {
            rooms.shutdown();
        }
    }
}
