package com.millionnaire.gateway.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.gateway.auth.UserStore.User;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** 所选头像只在网关保存，随 UPDATE 的 avatars 字段推给房间所有人；没选的不出现（客户端按玩家 ID 取默认头像）。 */
class AvatarBroadcastTest {
    @Test
    void chosenAvatarsTravelWithEveryUpdate() throws Exception {
        Clock clock = Clock.systemUTC();
        List<String> sent = new CopyOnWriteArrayList<>();
        RoomStore store = new RoomStore(new StaticListableBeanFactory().getBeanProvider(JdbcTemplate.class), clock);
        ObjectMapper json = new ObjectMapper();
        RoomService rooms = new RoomService(store, (player, msg) -> sent.add(msg), new Wire(json), clock);
        try {
            LiveRoom room = rooms.create(new User(1, "阿杰", 4), "c", null).room();   // 头像序号 3
            assertThat(rooms.join(new User(2, "糖糖"), room.codeNumber(), "j").ok()).isTrue();
            JsonNode last = json.readTree(sent.get(sent.size() - 1));
            assertThat(last.path("avatars").path("1").asInt(-1)).isEqualTo(3);
            assertThat(last.path("avatars").has("2")).isFalse();
            JsonNode snap = json.readTree(room.snapshot("2"));
            assertThat(snap.path("avatars").path("1").asInt(-1)).isEqualTo(3);
        } finally {
            rooms.shutdown();
        }
    }

    @Test
    void userAvatarIndexIsStoredPlusOne() {
        assertThat(new User(1, "a", 0).avatar()).isEqualTo(-1);
        assertThat(new User(1, "a", 1).avatar()).isEqualTo(0);
        assertThat(new User(1, "a", 8).avatar()).isEqualTo(7);
    }
}
