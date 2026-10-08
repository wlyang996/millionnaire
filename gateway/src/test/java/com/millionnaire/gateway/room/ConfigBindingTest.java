package com.millionnaire.gateway.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.gateway.auth.UserStore.User;
import com.millionnaire.gateway.config.GameConfigs;
import com.millionnaire.gateway.config.GameSettings;
import com.millionnaire.gateway.config.SettingsMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** 发布新参数后：新建的房间用新版本，已开的房间整局沿用建房时的版本；UPDATE 带 configId。 */
class ConfigBindingTest {
    @Test
    void defaultsReproduceTheEngineConfigExactly() {
        GameSettings d = SettingsMapper.defaults();
        assertThat(SettingsMapper.validate(d)).isEmpty();
        assertThat(SettingsMapper.toRuleConfig(d).contentHash()).isEqualTo(RuleConfigs.defaultV1().contentHash());
        assertThat(d.tileNames().get("classic-30")).hasSize(30);
        assertThat(d.tileNames().get("classic-50")).hasSize(50);
    }

    @Test
    void validationReportsReadableErrors() {
        GameSettings d = SettingsMapper.defaults();
        Map<String, Integer> events = new TreeMap<>(d.eventWeights());
        events.put("JAIL", 6);
        Map<String, List<String>> names = new TreeMap<>(d.tileNames());
        List<String> n30 = new ArrayList<>(names.get("classic-30"));
        n30.set(3, "这是一个特别特别长的地名");
        names.put("classic-30", n30);
        GameSettings bad = new GameSettings(d.tiers(), d.station(), d.fees(),
                new GameSettings.EventCash(100, 450, 100), events, d.cardWeights(), names);
        List<String> errors = SettingsMapper.validate(bad);
        assertThat(errors).anyMatch(e -> e.contains("事件概率合计必须为 100"));
        assertThat(errors).anyMatch(e -> e.contains("步长"));
        assertThat(errors).anyMatch(e -> e.contains("第 3 格名称超过"));
    }

    @Test
    void luckyPoolWeightsAndRewardAreEditable() {
        GameSettings d = SettingsMapper.defaults();
        assertThat(d.lucky()).extracting(GameSettings.LuckySetting::kind)
                .containsExactly("CASH_REWARD", "BUILD", "TO_STATION", "TO_START", "CASH_FINE", "DOWNGRADE", "MOVE", "JAIL");
        List<GameSettings.LuckySetting> lucky = new ArrayList<>(d.lucky());
        lucky.set(0, new GameSettings.LuckySetting("CASH_REWARD", "好人好事", 450, 40, false));
        lucky.set(3, new GameSettings.LuckySetting("TO_START", "回到起点", 0, 0, false));
        GameSettings edited = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames(), lucky);
        assertThat(SettingsMapper.validate(edited)).isEmpty();
        var board = SettingsMapper.toRuleConfig(edited).board("classic-30").orElseThrow();
        var pool = board.pool(com.millionnaire.engine.config.TileType.FIXED_EVENT);
        assertThat(pool).hasSize(3);
        assertThat(board.pool(com.millionnaire.engine.config.TileType.UNLUCKY_EVENT)).hasSize(4);
        assertThat(pool.get(0).amount()).isEqualTo(450);
        assertThat(pool.get(0).weight()).isEqualTo(40);

        List<GameSettings.LuckySetting> zero = d.lucky().stream()
                .map(l -> new GameSettings.LuckySetting(l.kind(), l.label(), l.amount(), l.unlucky() ? l.weight() : 0, l.unlucky())).toList();
        assertThat(SettingsMapper.validate(new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(),
                d.eventWeights(), d.cardWeights(), d.tileNames(), zero))).anyMatch(e -> e.contains("不能全为 0"));
        lucky.set(0, new GameSettings.LuckySetting("CASH_REWARD", "好人好事", 470, 40, false)); // 不符合 50 的步长
        assertThat(SettingsMapper.validate(new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(),
                d.eventWeights(), d.cardWeights(), d.tileNames(), lucky))).isNotEmpty();
        // 早先只有幸运奖池（4 项、没有 unlucky 字段）的快照：补齐不幸奖池后与默认一致
        assertThat(SettingsMapper.mergeLucky(d.lucky().subList(0, 4))).isEqualTo(d.lucky());
        // 旧版本快照没有 lucky：按默认处理，规则与默认一致
        GameSettings old = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames());
        assertThat(SettingsMapper.toRuleConfig(old).contentHash()).isEqualTo(RuleConfigs.defaultV1().contentHash());
    }

    @Test
    void rentRiseIsEditable() {
        GameSettings d = SettingsMapper.defaults();
        assertThat(d.rentRise()).isEqualTo(new GameSettings.RentRise(10, 5, 20, 300));
        GameSettings edited = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames(), d.lucky(), new GameSettings.RentRise(6, 3, 50, 400));
        assertThat(SettingsMapper.validate(edited)).isEmpty();
        var ri = SettingsMapper.toRuleConfig(edited).rentInflation();
        assertThat(ri.percent(com.millionnaire.engine.config.EndMode.BANKRUPTCY, 7)).isEqualTo(150);
        assertThat(ri.percent(com.millionnaire.engine.config.EndMode.BANKRUPTCY, 100)).isEqualTo(400);
        GameSettings bad = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames(), d.lucky(), new GameSettings.RentRise(-1, 0, 120, 50));
        assertThat(SettingsMapper.validate(bad)).hasSize(4);
        // 旧版本快照没有 rentRise：按默认处理，规则与默认一致
        GameSettings old = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames(), d.lucky());
        assertThat(SettingsMapper.toRuleConfig(old).contentHash()).isEqualTo(RuleConfigs.defaultV1().contentHash());
    }

    @Test
    void newRoomsUseThePublishedVersionOldRoomsKeepTheirs() throws Exception {
        Clock clock = Clock.systemUTC();
        List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        RoomStore store = new RoomStore(new StaticListableBeanFactory().getBeanProvider(JdbcTemplate.class), clock);
        ObjectMapper json = new ObjectMapper();
        GameConfigs configs = GameConfigs.inMemory(clock);
        RoomService rooms = new RoomService(store, (p, m) -> sent.add(m), new Wire(json), clock, configs);
        try {
            LiveRoom before = rooms.create(new User(1, "阿杰"), "a", null).room();
            GameSettings d = SettingsMapper.defaults();
            GameSettings changed = new GameSettings(d.tiers(), d.station(),
                    new GameSettings.Fees(2000, d.fees().miniGameWinReward(), d.fees().bailCost()),
                    d.eventCash(), d.eventWeights(), d.cardWeights(), d.tileNames());

            assertThatThrownBy(() -> configs.publish(changed, "x", "t", 7, null))
                    .isInstanceOf(ClientException.class).hasMessageContaining("刷新");
            GameConfigs.Published v1 = configs.publish(changed, "起点 2000", "t", 0, null);
            assertThat(v1.configId()).isEqualTo(1);
            assertThat(configs.current().engine().config().economy().startReward()).isEqualTo(2000);

            LiveRoom after = rooms.create(new User(2, "糖糖"), "b", null).room();
            assertThat(json.readTree(before.snapshot("1")).path("configId").asLong(-1)).isEqualTo(0);
            assertThat(json.readTree(after.snapshot("2")).path("configId").asLong(-1)).isEqualTo(1);

            GameConfigs.Published back = configs.rollback(0, "t", 1);
            assertThat(back.configId()).isEqualTo(2);
            assertThat(back.sourceConfigId()).isEqualTo(0L);
            assertThat(configs.current().engine().configHash()).isEqualTo(RuleConfigs.defaultV1().contentHash());
            assertThat(configs.history(10)).extracting(GameConfigs.Published::configId).containsExactly(2L, 1L, 0L);
        } finally {
            rooms.shutdown();
        }
    }
}
