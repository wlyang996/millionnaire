package com.millionnaire.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** 用 H2(MySQL 模式) 验证 db profile 接线：Flyway 能跑完 V1 基线，/health/db 能查到表。不证明真实 MySQL 5.7 行为。 */
@SpringBootTest
@ActiveProfiles("db")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:t;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.placeholders.table_options="
})
class DbProfileTest {
    @Autowired
    HealthController controller;

    @Test
    void migrationAppliedAndDiagnosticsUp() {
        Map<String, Object> r = controller.db();
        assertThat(r.get("db")).isEqualTo("UP");
        assertThat(r.get("flywayVersion")).isEqualTo("1");
        assertThat(r.get("appUserRows")).isEqualTo(0L);
    }
}
