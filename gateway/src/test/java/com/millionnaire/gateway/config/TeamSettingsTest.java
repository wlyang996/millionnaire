package com.millionnaire.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.millionnaire.engine.config.TeamConfig;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TeamSettingsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void oldSnapshotsAndDefaultsStayDisabled() throws Exception {
        var d = SettingsMapper.defaults();
        assertThat(d.team().enabled()).isFalse();
        var n = (ObjectNode) json.valueToTree(d); n.remove("team");
        var old = json.treeToValue(n, GameSettings.class);
        assertThat(old.team()).isNull();
        assertThat(SettingsMapper.normalized(old).team()).isEqualTo(TeamConfig.NONE);
        assertThat(SettingsMapper.toRuleConfig(old)).isEqualTo(SettingsMapper.toRuleConfig(d));
        assertThat(SettingsMapper.validate(old)).isEmpty();
    }

    @Test void publishBindsRulesAndClientReceivesValuesWithoutChangingPreviousVersion() throws Exception {
        var configs = GameConfigs.inMemory(Clock.systemUTC());
        var original = configs.current();
        var t = new TeamConfig(false, List.of(2,4), 4, false, true, false, true,
                800, 2, List.of(50L,200L,800L), true, 15000);
        var n = (ObjectNode) json.valueToTree(SettingsMapper.defaults()); n.set("team", json.valueToTree(t));
        var edited = json.treeToValue(n, GameSettings.class);
        assertThat(SettingsMapper.validate(edited)).isEmpty();
        var p = configs.publish(edited, "team rules", "test", 0, null);
        assertThat(configs.load(p.configId()).orElseThrow().settings().team()).isEqualTo(t);
        assertThat(configs.current().engine().config().team()).isEqualTo(t);
        assertThat(original.settings().team()).isEqualTo(TeamConfig.NONE);
        var body = new ClientConfigController(configs).client(p.configId()).getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("team")).isEqualTo(t);
        assertThat(new ClientConfigController(configs).client(0).getBody().get("team")).isEqualTo(TeamConfig.NONE);
        assertThat(p.ruleHash()).isNotEqualTo(original.engine().configHash());
    }

    @Test void invalidAidDoesNotPublishAndValidationIsReadable() throws Exception {
        var configs = GameConfigs.inMemory(Clock.systemUTC());
        var n = (ObjectNode) json.valueToTree(SettingsMapper.defaults());
        n.set("team", json.valueToTree(new TeamConfig(false, List.of(2), 4, true, true, true,
                true, 100, 11, List.of(100L,300L), true, 0)));
        var bad = json.treeToValue(n, GameSettings.class);
        assertThat(SettingsMapper.validate(bad)).anyMatch(e -> e.contains("默认形式"))
                .anyMatch(e -> e.contains("快捷金额")).anyMatch(e -> e.contains("次数"))
                .anyMatch(e -> e.contains("等待时长"));
        assertThatThrownBy(() -> configs.publish(bad, "bad", "test", 0, null))
                .isInstanceOf(com.millionnaire.gateway.room.ClientException.class);
        assertThat(configs.activeId()).isZero();
    }
}
