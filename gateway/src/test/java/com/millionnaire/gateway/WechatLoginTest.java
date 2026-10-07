package com.millionnaire.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.millionnaire.gateway.auth.WechatAuth;
import java.util.Map;
import java.util.Optional;
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
                    return Optional.empty();
                }
                return Optional.of("o-" + code.split("-")[1]);
            };
        }
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;
    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;

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
        assertThat(bad.body()).contains("WECHAT_LOGIN_FAILED");
        ResponseEntity<Map> nick = http.postForEntity("/api/auth/wx-login", Map.of("code", "c-carol-1", "nickname", "​"), Map.class);
        assertThat(nick.getStatusCode().value()).isEqualTo(400);
        ResponseEntity<Map> none = http.postForEntity("/api/auth/wx-login", Map.of(), Map.class);
        assertThat(none.getStatusCode().value()).isEqualTo(400);
    }
}
