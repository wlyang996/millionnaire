package com.millionnaire.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.millionnaire.engine.config.TeamConfig;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** 覆盖数据库读回路径，而非仅覆盖发布时的内存对象。 */
@SpringBootTest
@ActiveProfiles("db")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:teamconfig;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.flyway.placeholders.table_options="
})
class TeamConfigDbTest {
    @Autowired ObjectProvider<JdbcTemplate> jdbc;
    @Autowired Clock clock;

    @Test void freshLoaderKeepsTeamRulesAndFillsMissingHistoricalFieldsWithoutRewritingHash() throws Exception {
        var json = new ObjectMapper();
        var team = new TeamConfig(false, List.of(2,3), 3, false, true, false,
                true, 1000, 2, List.of(100L,800L), true, 15000);
        var node = (ObjectNode) json.valueToTree(SettingsMapper.defaults());
        node.set("team", json.valueToTree(team));
        var configs = new GameConfigs(jdbc, clock);
        var p = configs.publish(json.treeToValue(node, GameSettings.class), "team", "test", 0, null);
        var reloaded = new GameConfigs(jdbc, clock);
        assertThat(reloaded.load(p.configId()).orElseThrow().settings().team()).isEqualTo(team);
        assertThat(reloaded.current().engine().config().team()).isEqualTo(team);
        assertThat(reloaded.current().engine().configHash()).isEqualTo(p.ruleHash());

        // 模拟旧发布记录缺少新增字段：不得启用组队，也不得修改其记录中的历史哈希。
        node.remove("team");
        jdbc.getObject().update("UPDATE game_config SET payload = ? WHERE config_id = ?",
                json.writeValueAsString(node), p.configId());
        var historical = new GameConfigs(jdbc, clock).load(p.configId()).orElseThrow();
        assertThat(historical.settings().team()).isEqualTo(TeamConfig.NONE);
        assertThat(historical.settings().team().enabled()).isFalse();
        assertThat(historical.ruleHash()).isEqualTo(p.ruleHash());
        assertThat(SettingsMapper.validate(historical.settings())).isEmpty();
    }
}
