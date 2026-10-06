package com.millionnaire.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 默认不连库：没有任何 MYSQL_* 配置时服务也能启动，/health/db 返回 DISABLED。 */
@SpringBootTest
class DefaultProfileTest {
    @Autowired
    HealthController controller;

    @Test
    void startsWithoutDatabase() {
        assertThat(controller.health().get("status")).isEqualTo("UP");
        Map<String, Object> r = controller.db();
        assertThat(r.get("db")).isEqualTo("DISABLED");
    }
}
