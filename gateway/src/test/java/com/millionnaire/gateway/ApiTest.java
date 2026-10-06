package com.millionnaire.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 不连库（默认配置）时的 HTTP 接口：用户只在内存。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiTest {
    @Autowired
    TestRestTemplate http;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void testLoginAndMe() {
        ResponseEntity<Map> bad = http.postForEntity("/api/auth/test-login", Map.of("nickname", "  "), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().get("code")).isEqualTo("INVALID_NICKNAME");
        assertThat(http.postForEntity("/api/auth/test-login", Map.of("nickname", "a‮b"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> ok = http.postForObject("/api/auth/test-login", Map.of("nickname", " 奶茶 "), Map.class);
        assertThat(ok.get("nickname")).isEqualTo("奶茶");
        assertThat((String) ok.get("token")).hasSizeGreaterThan(20);

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth((String) ok.get("token"));
        ResponseEntity<Map> me = http.exchange("/api/me", HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("userId")).isEqualTo(ok.get("userId"));
        assertThat(me.getBody().get("roomCode")).isNull();

        assertThat(http.exchange("/api/room", HttpMethod.GET, new HttpEntity<>(h), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/me", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
