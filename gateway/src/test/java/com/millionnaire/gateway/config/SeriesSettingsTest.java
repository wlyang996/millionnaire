package com.millionnaire.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.millionnaire.engine.config.SeriesConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SeriesSettingsTest {
    @Test void publishedSeriesRulesMapValidateAndOldSnapshotsDoNotEnableSeries() throws Exception {
        var json = new ObjectMapper(); var d = SettingsMapper.defaults();
        var node = (ObjectNode) json.valueToTree(d);
        node.remove("series");
        var old = json.treeToValue(node, GameSettings.class);
        assertThat(SettingsMapper.seriesOf(old).enabled()).isFalse();
        assertThat(SettingsMapper.normalized(old).series().enabled()).isFalse();
        assertThat(SettingsMapper.toRuleConfig(d).series()).isEqualTo(SeriesConfig.DEFAULT);
        var edited = new SeriesConfig(true, List.of(4, 8), 4, List.of(100, 60, 40, 30, 20, 10, 0, 0), SeriesConfig.TieMode.SPLIT_OCCUPIED, false, 3);
        node.set("series", json.valueToTree(edited));
        var setting = json.treeToValue(node, GameSettings.class);
        assertThat(SettingsMapper.validate(setting)).isEmpty();
        assertThat(SettingsMapper.toRuleConfig(setting).series()).isEqualTo(edited);
        node.set("series", json.valueToTree(new SeriesConfig(true, List.of(4), 8, List.of(0, 1, 0, 0, 0, 0, 0, 0), null, true, 1)));
        assertThat(SettingsMapper.validate(json.treeToValue(node, GameSettings.class))).anyMatch(e -> e.contains("系列赛"));
    }
}
