package com.millionnaire.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.millionnaire.gateway.record.Dashboard;
import com.millionnaire.gateway.room.ClientException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConfigHistoryTest {
    private final Clock clock = Clock.systemUTC();
    private final GameConfigs configs = GameConfigs.inMemory(clock);

    @Test
    void databaseAndMemoryPagesMatchBeyondFiftyIncludingDefaultAndLiteralSearch() {
        var defaults = configs.load(0).orElseThrow();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:history" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE game_config(config_id BIGINT PRIMARY KEY, payload VARCHAR(100), note VARCHAR(200),"
                + " created_by VARCHAR(64), created_at BIGINT, source_config_id BIGINT, rule_hash VARCHAR(64))");
        List<GameConfigs.Published> versions = new ArrayList<>();
        for (long id = 1; id <= 75; id++) {
            Long source = id % 15 == 0 ? 0L : null;
            String note = id == 1 ? "first 100%_! special" : "Release " + id;
            String author = id == 1 ? "admin" : id % 2 == 0 ? "Alice" : "Bob";
            versions.add(new GameConfigs.Published(id, defaults.settings(), "hash" + id, note, author, id, source));
            // Invalid payload deliberately demonstrates that history queries only read metadata.
            jdbc.update("INSERT INTO game_config VALUES(?,?,?,?,?,?,?)", id, "not-json", note, author, id, source, "hash" + id);
        }
        var queries = List.of(
                new ConfigHistory.Query(1, 20, "", "all", "all"),
                new ConfigHistory.Query(4, 20, "", "all", "all"),
                new ConfigHistory.Query(9, 10, "", "all", "all"),
                new ConfigHistory.Query(1, 20, "ALICE", "publish", "inactive"),
                new ConfigHistory.Query(1, 20, "", "rollback", "all"),
                new ConfigHistory.Query(1, 20, "100%_!", "all", "all"),
                new ConfigHistory.Query(1, 20, "0", "all", "all"),
                new ConfigHistory.Query(1, 20, "", "default", "inactive"),
                new ConfigHistory.Query(1, 20, "", "all", "active"),
                new ConfigHistory.Query(1, 20, "系统", "all", "all"),
                new ConfigHistory.Query(1, 20, "管理员", "all", "all"),
                new ConfigHistory.Query(1, 20, "missing", "all", "all"));
        for (var query : queries) {
            assertThat(ConfigHistory.database(jdbc, defaults, 75, query))
                    .as("query %s", query).isEqualTo(ConfigHistory.memory(versions, defaults, 75, query));
        }
        var last = ConfigHistory.database(jdbc, defaults, 75, queries.get(1));
        assertThat(last.total()).isEqualTo(76);
        assertThat(last.items()).extracting(ConfigHistory.Entry::configId)
                .containsExactly(15L, 14L, 13L, 12L, 11L, 10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L, 0L);
        assertThat(ConfigHistory.database(jdbc, defaults, 75, queries.get(5)).items()).hasSize(1);
        assertThat(ConfigHistory.database(jdbc, defaults, 75, queries.get(6)).items()).hasSize(12);
        assertThat(ConfigHistory.database(jdbc, defaults, 75,
                new ConfigHistory.Query(1, 20, "0", "rollback", "all")).items()).hasSize(5);
        assertThat(configs.historyPage(new ConfigHistory.Query(1, 20, "系统", "all", "all")).items())
                .extracting(ConfigHistory.Entry::configId).containsExactly(0L);
        configs.publish(SettingsMapper.defaults(), "name search", "admin", 0, null);
        assertThat(configs.historyPage(new ConfigHistory.Query(1, 20, "管理员", "all", "all")).items())
                .extracting(ConfigHistory.Entry::configId).containsExactly(1L);
    }

    @Test
    void historyEndpointRequiresAuthenticationAndPreservesLegacyListContract() throws Exception {
        var policy=new com.millionnaire.gateway.admin.AdminPolicy();policy.setBootstrapUsername("admin.history");policy.setBootstrapPassword("history-test-password");policy.setBcryptStrength(4);policy.setAllowInMemory(true);
        var authentication=new com.millionnaire.gateway.admin.AdminAuth(new com.millionnaire.gateway.admin.AdminStore(() -> null),clock,policy);
        var first=authentication.login("admin.history","history-test-password","local","test","login");
        var actor=authentication.authenticate("Bearer "+first.get("token"));authentication.changePassword(actor,"history-test-password","history-changed-password","change");
        String auth="Bearer "+authentication.login("admin.history","history-changed-password","local","test","login").get("token");
        var controller = new AdminController(configs, mock(Dashboard.class), authentication);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new com.millionnaire.gateway.admin.AdminWeb.Errors()).build();
        mvc.perform(get("/admin-api/v1/config/history/page")).andExpect(status().isUnauthorized());
        assertThat(controller.history(auth).getBody()).isInstanceOf(List.class);
        var page = (ConfigHistory.Page) controller.historyPage(auth, 1, 20, "", "all", "active").getBody();
        assertThat(page.items()).extracting(ConfigHistory.Entry::configId).containsExactly(0L);
        mvc.perform(get("/admin-api/v1/config/history/page").header("Authorization", auth).param("page", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin-api/v1/config/history/page").header("Authorization", auth).param("size", "101"))
                .andExpect(status().isBadRequest());
        assertThatThrownBy(() -> new ConfigHistory.Query(1, 20, "", "anything", "all"))
                .isInstanceOf(ClientException.class);
    }
}
