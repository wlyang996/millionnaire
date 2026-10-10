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
    private static com.millionnaire.engine.config.RuleConfig legacyDefaults() {
        var c = RuleConfigs.defaultV1();
        return new com.millionnaire.engine.config.RuleConfig(c.ruleVersion(), c.boards(), c.tiers(), c.station(), c.economy(), c.ratios(),
                c.cardWeights(), c.eventWeights(), c.timing(), c.room(), c.rentInflation(), c.setBonus(), c.startPick());
    }

    @Test void globalGameplaySurvivesPublishingAndOldJsonDoesNotEnableIt() throws Exception {
        var json = new ObjectMapper(); var defaults = SettingsMapper.defaults();
        var configs = GameConfigs.inMemory(Clock.systemUTC());
        var p = configs.publish(defaults, "global", "test", 0, null);
        var actual = configs.load(p.configId()).orElseThrow();
        assertThat(actual.settings().roundReward()).isEqualTo(defaults.roundReward());
        assertThat(actual.settings().cityEvents()).isEqualTo(defaults.cityEvents());
        assertThat(actual.settings().funTitles()).isEqualTo(defaults.funTitles());
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(defaults);
        tree.remove(List.of("roundReward", "cityEvents", "funTitles"));
        var old = SettingsMapper.normalized(json.treeToValue(tree, GameSettings.class));
        assertThat(old.roundReward().enabled()).isFalse();
        assertThat(old.cityEvents().enabled()).isFalse();
        assertThat(old.funTitles().enabled()).isFalse();
        assertThat(SettingsMapper.toRuleConfig(old)).isEqualTo(legacyDefaults());
        assertThat(SettingsMapper.validate(old)).isEmpty();
    }

    @Test
    void defaultsReproduceTheEngineConfigExactly() {
        GameSettings d = SettingsMapper.defaults();
        assertThat(SettingsMapper.validate(d)).isEmpty();
        assertThat(d.timing().animPerStepMs()).isEqualTo(550);
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
        assertThat(SettingsMapper.toRuleConfig(old).contentHash()).isEqualTo(legacyDefaults().contentHash());
    }

    @Test
    void timingAndAnnouncementAreEditable() {
        GameSettings d = SettingsMapper.defaults();
        assertThat(d.timing().decisionSeconds()).isEqualTo(15);
        var t = new GameSettings.TimingSetting(20, 12, 15, 15, 8, 30, 3, 60, 40, 1200, 200, 800, 3, 4200, 2400, 1900, 2700);
        var notice = new GameSettings.Announcement(true, "周末活动", "周末起点奖励翻倍！");
        GameSettings edited = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise(), d.handLimit(), d.room(), d.sets(), t, notice);
        assertThat(SettingsMapper.validate(edited)).isEmpty();
        var timing = SettingsMapper.toRuleConfig(edited).timing();
        assertThat(timing.decisionWindowMs()).isEqualTo(20_000);
        assertThat(timing.auctionMaxMs()).isEqualTo(60_000);
        assertThat(timing.animPerStepMs()).isEqualTo(200);
        assertThat(timing.allAwayTurns()).isEqualTo(3);
        assertThat(timing.eventPresentationMs()).isEqualTo(4200);
        assertThat(timing.eventCashPresentationMs()).isEqualTo(2400);
        assertThat(timing.startPickPresentationMs()).isEqualTo(1900);
        assertThat(timing.jailPresentationMs()).isEqualTo(2700);
        var oldTiming = new GameSettings.TimingSetting(20, 12, 15, 15, 8, 30, 3, 60, 40, 1200, 200, 800, 3);
        var oldSettings = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise(), d.handLimit(), d.room(), d.sets(), oldTiming, null);
        assertThat(SettingsMapper.toRuleConfig(oldSettings).timing().eventPresentationMs())
                .isEqualTo(d.timing().eventPresentationMs().longValue());
        assertThat(SettingsMapper.timingOf(new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(),
                d.eventWeights(), d.cardWeights(), d.tileNames())).allAwayTurns()).isEqualTo(1);
        assertThat(timing.heartbeatMs()).isEqualTo(RuleConfigs.defaultV1().timing().heartbeatMs());
        // 公告不影响规则
        assertThat(SettingsMapper.toRuleConfig(edited).contentHash()).isEqualTo(SettingsMapper.toRuleConfig(
                new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(), d.cardWeights(),
                        d.tileNames(), d.lucky(), d.rentRise(), d.handLimit(), d.room(), d.sets(), t, null)).contentHash());

        var bad = new GameSettings.TimingSetting(2, 12, 15, 15, 8, 30, 3, 20, 40, 1200, 200, 800);
        List<String> errors = SettingsMapper.validate(new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(),
                d.eventWeights(), d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise(), d.handLimit(), d.room(), d.sets(),
                bad, new GameSettings.Announcement(true, "", "")));
        assertThat(errors).anyMatch(e -> e.contains("选择时限"));
        assertThat(errors).anyMatch(e -> e.contains("拍卖最长时长不能小于"));
        assertThat(errors).anyMatch(e -> e.contains("公告时内容不能为空"));
        // 旧版本快照没有 timing：按默认处理
        assertThat(SettingsMapper.toRuleConfig(new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(),
                d.eventWeights(), d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise(), d.handLimit(), d.room(), d.sets()))
                .contentHash()).isEqualTo(legacyDefaults().contentHash());
    }

    @Test
    void sharedAnimationModeIsConfigurableAndOldRoomSettingsRemainCompatible() throws Exception {
        GameSettings d = SettingsMapper.defaults(); var r = d.room();
        var fast = new GameSettings.RoomSetting(r.initialCashOptions(), r.defaultInitialCash(), r.timeLimitMinutesOptions(),
                r.defaultTimeLimitMinutes(), r.rollSecondsOptions(), r.defaultRollSeconds(), r.defaultEndMode(),
                r.bankruptcyCapMinutes(), true, true, 40);
        GameSettings edited = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise(), d.handLimit(), fast);
        assertThat(SettingsMapper.validate(edited)).isEmpty();
        var config = SettingsMapper.toRuleConfig(edited);
        assertThat(config.room().fastAnimationPercent()).isEqualTo(40);
        assertThat(com.millionnaire.engine.core.state.RoomSettings.defaults(config).fastMode()).isTrue();
        var old = new ObjectMapper().readValue("{\"boardId\":\"classic-30\",\"initialCash\":3000,\"endMode\":\"TIME_LIMIT\",\"timeLimitMinutes\":30,\"rollSeconds\":15,\"initialCards\":0}",
                com.millionnaire.engine.core.state.RoomSettings.class);
        assertThat(old.fastMode()).isFalse();
        var oldRoom = new GameSettings.RoomSetting(r.initialCashOptions(), r.defaultInitialCash(), r.timeLimitMinutesOptions(),
                r.defaultTimeLimitMinutes(), r.rollSecondsOptions(), r.defaultRollSeconds(), r.defaultEndMode(), r.bankruptcyCapMinutes());
        var oldSettings = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise(), d.handLimit(), oldRoom);
        assertThat(SettingsMapper.toRuleConfig(oldSettings).contentHash()).isEqualTo(legacyDefaults().contentHash());
    }

    @Test
    void startPickIsEditable() {
        GameSettings d = SettingsMapper.defaults();
        assertThat(d.startPick().cashWeight()).isEqualTo(60);
        var p = new GameSettings.StartPickSetting(0, 100, 300, 600, 100);
        GameSettings edited = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise(), d.handLimit(), d.room(), d.sets(), d.timing(), null, p);
        assertThat(SettingsMapper.validate(edited)).isEmpty();
        var sp = SettingsMapper.toRuleConfig(edited).startPick();
        assertThat(sp.cardWeight()).isEqualTo(100);
        assertThat(sp.cashMax()).isEqualTo(600);
        List<String> errors = SettingsMapper.validate(new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(),
                d.eventWeights(), d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise(), d.handLimit(), d.room(), d.sets(),
                d.timing(), null, new GameSettings.StartPickSetting(-1, 10, 300, 650, 100)));
        assertThat(errors).anyMatch(e -> e.contains("权重"));
        assertThat(errors).anyMatch(e -> e.contains("整除"));
    }

    @Test
    void setBonusIsEditable() {
        GameSettings d = SettingsMapper.defaults();
        assertThat(d.sets().rentPercent()).isEqualTo(150);
        List<Integer> g30 = new ArrayList<>(d.sets().groups().get("classic-30"));
        g30.set(5, 1); // 第 5 格（高价）并入第 1 组：1、3、5 一组
        Map<String, List<Integer>> groups = new TreeMap<>(d.sets().groups());
        groups.put("classic-30", g30);
        GameSettings edited = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise(), d.handLimit(), d.room(),
                new GameSettings.SetSetting(200, groups));
        // 第 6 格原来和第 5 格一组，现在落单
        assertThat(SettingsMapper.validate(edited)).anyMatch(e -> e.contains("只有 1 块地"));
        g30.set(6, 0);
        assertThat(SettingsMapper.validate(edited)).isEmpty();
        var rc = SettingsMapper.toRuleConfig(edited);
        assertThat(rc.setBonus().members("classic-30", 5)).containsExactly(1, 3, 5);
        assertThat(rc.setBonus().rentPercent()).isEqualTo(200);
        g30.set(4, 2); // 车站
        assertThat(SettingsMapper.validate(edited)).anyMatch(e -> e.contains("不是普通地产"));
        // 旧版本快照没有 sets：按默认处理
        GameSettings old = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise(), d.handLimit(), d.room());
        assertThat(SettingsMapper.toRuleConfig(old).contentHash()).isEqualTo(legacyDefaults().contentHash());
    }

    @Test
    void roomChoicesAreEditable() {
        GameSettings d = SettingsMapper.defaults();
        assertThat(d.room().rollSecondsOptions()).containsExactly(15, 30, 45, 60);
        var room = new GameSettings.RoomSetting(List.of(10000L, 1000L), 1000, List.of(20, 10), 20,
                List.of(20, 10), 10, "BANKRUPTCY", 90);
        GameSettings edited = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise(), d.handLimit(), room);
        assertThat(SettingsMapper.validate(edited)).isEmpty();
        assertThat(SettingsMapper.normalized(edited).room().rollSecondsOptions()).containsExactly(10, 20);
        var rc = SettingsMapper.toRuleConfig(SettingsMapper.normalized(edited));
        assertThat(rc.timing().rollSecondsOptions()).containsExactly(10, 20);
        assertThat(rc.timing().bankruptcyModeCapMinutes()).isEqualTo(90);
        var defaults = com.millionnaire.engine.core.state.RoomSettings.defaults(rc);
        assertThat(defaults.initialCash()).isEqualTo(1000);
        assertThat(defaults.rollSeconds()).isEqualTo(10);
        assertThat(defaults.endMode()).isEqualTo(com.millionnaire.engine.config.EndMode.BANKRUPTCY);

        var bad = new GameSettings.RoomSetting(List.of(), 3000, List.of(30, 30), 45, List.of(1, 2, 3, 4, 5, 6), 1, "X", 5);
        List<String> errors = SettingsMapper.validate(new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(),
                d.eventWeights(), d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise(), d.handLimit(), bad));
        assertThat(errors).anyMatch(e -> e.contains("初始现金选项"));
        assertThat(errors).anyMatch(e -> e.contains("重复"));
        assertThat(errors).anyMatch(e -> e.contains("投骰时间选项"));
        assertThat(errors).anyMatch(e -> e.contains("结束模式"));
        assertThat(errors).anyMatch(e -> e.contains("破产模式最长"));
        // 旧版本快照没有 room：按默认处理
        GameSettings old = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise(), d.handLimit());
        assertThat(SettingsMapper.toRuleConfig(old).contentHash()).isEqualTo(legacyDefaults().contentHash());
    }

    @Test
    void handLimitIsEditable() {
        GameSettings d = SettingsMapper.defaults();
        assertThat(d.handLimit()).isEqualTo(6);
        GameSettings edited = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise(), 8);
        assertThat(SettingsMapper.validate(edited)).isEmpty();
        assertThat(SettingsMapper.toRuleConfig(edited).economy().handLimit()).isEqualTo(8);
        for (int bad : new int[] {1, 9}) {
            assertThat(SettingsMapper.validate(new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(),
                    d.eventWeights(), d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise(), bad)))
                    .anyMatch(e -> e.contains("道具上限"));
        }
        // 旧版本快照没有 handLimit：按默认处理
        GameSettings old = new GameSettings(d.tiers(), d.station(), d.fees(), d.eventCash(), d.eventWeights(),
                d.cardWeights(), d.tileNames(), d.lucky(), d.rentRise());
        assertThat(SettingsMapper.toRuleConfig(old).contentHash()).isEqualTo(legacyDefaults().contentHash());
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
        assertThat(SettingsMapper.toRuleConfig(old).contentHash()).isEqualTo(legacyDefaults().contentHash());
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
