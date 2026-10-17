package com.millionnaire.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.millionnaire.gateway.auth.WechatAuth;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** 微信登录：code 换 openid（假的换取器），首次要资料、再次直接登录，openid 绑定落库。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("db")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:wx;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.placeholders.table_options=",
        "millionnaire.wechat.app-id=wx-test-app",
        "millionnaire.wechat.app-secret=secret"
})
class WechatLoginTest {
    @TestConfiguration
    static class FakeWechat {
        /** code "c-<名字>-<序号>" → openid "o-<名字>"；其他 code 无效。 */
        @Bean
        WechatAuth.CodeExchanger fakeExchanger() {
            return (appId, secret, code) -> {
                assertThat(appId).isEqualTo("wx-test-app");
                if (!code.startsWith("c-")) {
                    return WechatAuth.Exchange.fail(40029, "invalid code");
                }
                return WechatAuth.Exchange.ok("o-" + code.split("-")[1]);
            };
        }
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;
    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired com.millionnaire.gateway.room.RoomService rooms;
    @Autowired com.millionnaire.gateway.auth.UserStore users;

    @org.junit.jupiter.api.BeforeEach
    void h2RoomByteLengthCheck() {
        // H2 decodes VARBINARY in LENGTH; the production MySQL constraint counts bytes.
        jdbc.execute("ALTER TABLE room DROP CONSTRAINT IF EXISTS ck_room_ids");
        jdbc.execute("ALTER TABLE room ADD CONSTRAINT ck_room_ids CHECK (OCTET_LENGTH(create_request_id) = 16 AND max_saved_game_no >= 0)");
    }

    private ResponseEntity<Map> profile(String token, Map<String, Object> body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange("/api/me/profile", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @Test void authenticatedProfileChangesKeepTheSameIdentityAndPersistForLaterLogin() {
        var first = wx(Map.of("code", "c-profile-1", "nickname", "旧昵称", "avatar", 0));
        var other = wx(Map.of("code", "c-otherprofile-1", "nickname", "其他人", "avatar", 2));
        String uid = (String)first.get("userId"), token = (String)first.get("token");
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class);
        var saved = profile(token, Map.of("nickname", "新昵称", "avatar", 1, "userId", other.get("userId")));
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        assertThat(saved.getBody()).containsEntry("userId", uid).containsEntry("nickname", "新昵称")
                .containsEntry("avatar", 1).containsEntry("appliesNextRoom", false).doesNotContainKey("token");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(before);
        assertThat(wx(Map.of("code", "c-profile-2"))).containsEntry("userId", uid).containsEntry("nickname", "新昵称").containsEntry("avatar", 1);
        assertThat(wx(Map.of("code", "c-otherprofile-2"))).containsEntry("nickname", "其他人").containsEntry("avatar", 2);
        var headers = new HttpHeaders();headers.setBearerAuth(token);
        assertThat(http.exchange("/api/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody()).containsEntry("nickname", "新昵称");
    }

    @Test void invalidOrUnauthenticatedProfileChangesDoNotOverwriteSavedData() {
        var first = wx(Map.of("code", "c-invalidprofile-1", "nickname", "保留资料", "avatar", 0));
        String token = (String)first.get("token");
        assertThat(http.postForEntity("/api/me/profile", Map.of("nickname", "别人", "avatar", 1), Map.class).getStatusCode().value()).isEqualTo(401);
        assertThat(profile(token, Map.of("nickname", "​", "avatar", 1)).getStatusCode().value()).isEqualTo(400);
        assertThat(profile(token, Map.of("nickname", "不能保存", "avatar", 8)).getStatusCode().is4xxClientError()).isTrue();
        assertThat(wx(Map.of("code", "c-invalidprofile-2"))).containsEntry("nickname", "保留资料").containsEntry("avatar", 0);
    }

    @Test void editingProfileInARoomKeepsTheActiveRoomAvatarAndMemberName() {
        var first = wx(Map.of("code", "c-roomprofile-1", "nickname", "房内旧名", "avatar", 0));
        var current = users.find(Long.parseLong((String)first.get("userId"))).orElseThrow();
        var created = rooms.create(current, "profile-room", null);
        var saved = profile((String)first.get("token"), Map.of("nickname", "房外新名", "avatar", 1));
        assertThat(saved.getBody()).containsEntry("appliesNextRoom", true);
        assertThat(rooms.avatarsOf(java.util.List.of(current.playerId()))).containsEntry(current.playerId(), 0);
        assertThat(created.room().view(current.playerId()).toString()).contains("房内旧名").doesNotContain("房外新名");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> wx(Map<String, Object> body) {
        return http.postForObject("/api/auth/wx-login", body, Map.class);
    }

    @Test
    void firstLoginNeedsAProfileThenTheSameWechatUserComesBack() {
        assertThat(http.getForObject("/api/auth/methods", Map.class)).containsEntry("wechat", true);
        assertThat(wx(Map.of("code", "c-alice-1"))).containsEntry("needProfile", true);

        Map<String, Object> first = wx(Map.of("code", "c-alice-2", "nickname", "阿杰", "avatar", 3));
        String uid = (String) first.get("userId");
        assertThat(first.get("token")).isNotNull();
        assertThat(first).containsEntry("nickname", "阿杰").containsEntry("avatar", 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wx_identity WHERE user_id = ?", Integer.class, Long.parseLong(uid)))
                .isEqualTo(1);

        Map<String, Object> again = wx(Map.of("code", "c-alice-3"));
        assertThat(again).containsEntry("userId", uid).containsEntry("nickname", "阿杰").containsEntry("avatar", 3);

        Map<String, Object> renamed = wx(Map.of("code", "c-alice-4", "nickname", "糖糖", "avatar", 5));
        assertThat(renamed).containsEntry("userId", uid).containsEntry("nickname", "糖糖").containsEntry("avatar", 5);
        assertThat(jdbc.queryForObject("SELECT avatar_id FROM app_user WHERE user_id = ?", Integer.class, Long.parseLong(uid)))
                .isEqualTo(6);

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth((String) again.get("token"));
        ResponseEntity<Map> me = http.exchange("/api/me", HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(me.getBody()).containsEntry("userId", uid);

        Map<String, Object> other = wx(Map.of("code", "c-bob-1", "nickname", "可可"));
        assertThat(other.get("userId")).isNotEqualTo(uid);
    }

    @Test
    void badCodesAndNicknamesAreRejected() throws Exception {
        // 401 用 JDK HttpClient 发（TestRestTemplate 的默认连接在 POST 收到 401 时读不出响应体）
        java.net.http.HttpResponse<String> bad = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/auth/wx-login"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"code\":\"nope\"}")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(bad.statusCode()).isEqualTo(401);
        assertThat(bad.body()).contains("WECHAT_LOGIN_FAILED").contains("40029");
        ResponseEntity<Map> nick = http.postForEntity("/api/auth/wx-login", Map.of("code", "c-carol-1", "nickname", "​"), Map.class);
        assertThat(nick.getStatusCode().value()).isEqualTo(400);
        ResponseEntity<Map> none = http.postForEntity("/api/auth/wx-login", Map.of(), Map.class);
        assertThat(none.getStatusCode().value()).isEqualTo(400);
    }
}
