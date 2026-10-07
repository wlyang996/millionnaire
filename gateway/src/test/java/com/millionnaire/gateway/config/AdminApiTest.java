package com.millionnaire.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

/** 管理后台接口：密码登录、读取、校验、发布、回滚；客户端按 configId 拉取地名与价格。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "millionnaire.admin.password=test-secret")
class AdminApiTest {
    @Autowired
    TestRestTemplate http;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void publishAndRollback() {
        assertThat(http.postForEntity("/admin-api/v1/auth/login", Map.of("password", "nope"), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.getForEntity("/admin-api/v1/config/active", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        Map<String, Object> login = http.postForObject("/admin-api/v1/auth/login",
                Map.of("password", "test-secret"), Map.class);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth((String) login.get("token"));

        Map<String, Object> active = http.exchange("/admin-api/v1/config/active", HttpMethod.GET,
                new HttpEntity<>(h), Map.class).getBody();
        long activeId = ((Number) active.get("configId")).longValue();
        Map<String, Object> settings = (Map<String, Object>) active.get("settings");
        Map<String, List<String>> names = (Map<String, List<String>>) settings.get("tileNames");
        names.get("classic-30").set(1, "新地名");

        ResponseEntity<Map> stale = http.exchange("/admin-api/v1/config/publish", HttpMethod.POST,
                new HttpEntity<>(Map.of("settings", settings, "note", "改名", "expectedActiveId", activeId + 99), h),
                Map.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        Map<String, Object> eventWeights = (Map<String, Object>) settings.get("eventWeights");
        eventWeights.put("JAIL", 50);
        Map<String, Object> v = http.exchange("/admin-api/v1/config/validate", HttpMethod.POST,
                new HttpEntity<>(settings, h), Map.class).getBody();
        assertThat((List<String>) v.get("errors")).isNotEmpty();
        eventWeights.put("JAIL", 5);

        ResponseEntity<Map> pub = http.exchange("/admin-api/v1/config/publish", HttpMethod.POST,
                new HttpEntity<>(Map.of("settings", settings, "note", "改名", "expectedActiveId", activeId), h),
                Map.class);
        assertThat(pub.getStatusCode()).isEqualTo(HttpStatus.OK);
        long newId = ((Number) pub.getBody().get("configId")).longValue();

        Map<String, Object> client = http.getForObject("/api/configs/" + newId + "/client", Map.class);
        assertThat(((Map<String, List<String>>) client.get("tileNames")).get("classic-30").get(1)).isEqualTo("新地名");
        assertThat(http.getForEntity("/api/configs/999999/client", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> rb = http.exchange("/admin-api/v1/config/rollback", HttpMethod.POST,
                new HttpEntity<>(Map.of("configId", activeId, "expectedActiveId", newId), h), Map.class);
        assertThat(rb.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> history = http.exchange("/admin-api/v1/config/history", HttpMethod.GET,
                new HttpEntity<>(h), List.class).getBody();
        assertThat(history.get(0)).containsEntry("active", true);
    }
}
