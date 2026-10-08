package com.millionnaire.gateway.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.engine.config.BoardTemplate;
import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.ConfigValidator;
import com.millionnaire.engine.config.EconomyConfig;
import com.millionnaire.engine.config.EventKind;
import com.millionnaire.engine.config.FixedEvent;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.config.StationPricing;
import com.millionnaire.engine.config.Tier;
import com.millionnaire.engine.config.TileType;
import com.millionnaire.engine.config.TierPricing;
import com.millionnaire.gateway.config.GameSettings.EventCash;
import com.millionnaire.gateway.config.GameSettings.Fees;
import com.millionnaire.engine.config.RentInflation;
import com.millionnaire.gateway.config.GameSettings.LuckySetting;
import com.millionnaire.gateway.config.GameSettings.RentRise;
import com.millionnaire.gateway.config.GameSettings.RoomSetting;
import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RoomOptions;
import com.millionnaire.gateway.config.GameSettings.StationSetting;
import com.millionnaire.gateway.config.GameSettings.TierSetting;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** GameSettings 与引擎 RuleConfig 之间的转换与校验。规则数值的最终约束沿用引擎的 {@link ConfigValidator}。 */
public final class SettingsMapper {
    /** 管理后台的业务上限：防止误输入（多打几个 0）做出离谱的经济系统。引擎自身的上限更宽。 */
    static final long MAX_PRICE = 1_000_000;
    static final int MAX_NAME_LENGTH = 8;
    /** 道具上限的业务范围：弃牌界面一屏最多摆 9 张（上限 + 新抽的 1 张）。 */
    static final int MAX_HAND_LIMIT = 8;

    private static final RuleConfig BASE = RuleConfigs.defaultV1();
    private static final Map<String, List<String>> DEFAULT_NAMES = loadDefaultNames();

    private SettingsMapper() {
    }

    /** 内置默认参数：引擎正式配置 + 设计稿地名（与改造前的游戏完全一致）。 */
    public static GameSettings defaults() {
        List<TierSetting> tiers = new ArrayList<>();
        for (TierPricing t : BASE.tiers()) {
            tiers.add(new TierSetting(t.tier().name(), t.basePrice(), t.upgradeCost(), List.copyOf(t.rents())));
        }
        EconomyConfig e = BASE.economy();
        Map<String, Integer> events = new LinkedHashMap<>();
        BASE.eventWeights().forEach((k, v) -> events.put(k.name(), v));
        Map<String, Integer> cards = new LinkedHashMap<>();
        BASE.cardWeights().forEach((k, v) -> cards.put(k.name(), v));
        return new GameSettings(tiers,
                new StationSetting(BASE.station().price(), BASE.station().rentPerStation()),
                new Fees(e.startReward(), e.miniGameWinReward(), e.bailCost()),
                new EventCash(e.eventCashMin(), e.eventCashMax(), e.eventCashStep()),
                events, cards, DEFAULT_NAMES, defaultLucky(), defaultRentRise(), e.handLimit(),
                defaultRoom());
    }

    /** 内置租金上涨参数（引擎默认）。 */
    public static RentRise defaultRentRise() {
        RentInflation r = BASE.rentInflation();
        return new RentRise(r.freeRounds(), r.everyRounds(), r.stepPercent(), r.capPercent());
    }

    /** 内置建房可选项（引擎默认）。 */
    public static RoomSetting defaultRoom() {
        RoomOptions r = BASE.room();
        return new RoomSetting(List.copyOf(r.initialCashOptions()), r.defaultInitialCash(),
                List.copyOf(BASE.timing().timeLimitMinutesOptions()), r.defaultTimeLimitMinutes(),
                List.copyOf(BASE.timing().rollSecondsOptions()), r.defaultRollSeconds(),
                r.defaultEndMode().name(), BASE.timing().bankruptcyModeCapMinutes());
    }

    /** 没有建房可选项（旧版本快照）时用内置默认。 */
    public static RoomSetting roomOf(GameSettings s) {
        return s.room() == null ? defaultRoom() : s.room();
    }

    /** 没有道具上限（旧版本快照）时用内置默认。 */
    public static int handLimitOf(GameSettings s) {
        return s.handLimit() == null ? BASE.economy().handLimit() : s.handLimit();
    }

    /** 没有租金上涨参数（旧版本快照）时用内置默认。 */
    public static RentRise rentRiseOf(GameSettings s) {
        return s.rentRise() == null ? defaultRentRise() : s.rentRise();
    }

    /** 内置幸运 / 不幸奖池（两张地图共用，取引擎正式配置）。 */
    static List<LuckySetting> defaultLucky() {
        List<LuckySetting> out = new ArrayList<>();
        for (FixedEvent f : RuleConfigs.LUCKY_AND_UNLUCKY) {
            out.add(new LuckySetting(f.kind().name(), f.label(), f.amount(), f.weight(), f.unlucky()));
        }
        return List.copyOf(out);
    }

    private static List<LuckySetting> luckyOf(GameSettings s) {
        return mergeLucky(s.lucky());
    }

    /**
     * 按（种类，幸运 / 不幸）对齐到内置奖池：没给的项用默认值。用于读旧版本快照（没有奖池，或只有幸运奖池）。
     */
    public static List<LuckySetting> mergeLucky(List<LuckySetting> given) {
        List<LuckySetting> out = new ArrayList<>();
        for (LuckySetting b : defaultLucky()) {
            LuckySetting g = given == null ? null : given.stream()
                    .filter(x -> x != null && b.kind().equals(x.kind()) && b.unlucky() == x.unlucky()).findFirst().orElse(null);
            out.add(g == null ? b : new LuckySetting(b.kind(), b.label(), g.amount(), g.weight(), b.unlucky()));
        }
        return List.copyOf(out);
    }

    /** 校验：返回中文错误列表（空表示可以发布）。 */
    public static List<String> validate(GameSettings s) {
        List<String> errors = new ArrayList<>();
        if (s == null) {
            return List.of("配置为空");
        }
        checkStructure(s, errors);
        if (!errors.isEmpty()) {
            return errors;
        }
        try {
            for (String e : ConfigValidator.validate(toRuleConfig(s))) {
                errors.add("规则校验：" + e);
            }
        } catch (RuntimeException e) {
            errors.add("规则校验失败：" + e.getMessage());
        }
        return errors;
    }

    private static void checkStructure(GameSettings s, List<String> errors) {
        if (s.tiers() == null || s.tiers().size() != 3) {
            errors.add("地产档位必须正好三档（低价、中价、高价）");
        } else {
            Map<String, Boolean> seen = new TreeMap<>();
            for (TierSetting t : s.tiers()) {
                String label = tierLabel(t == null ? null : t.tier());
                if (t == null || label == null) {
                    errors.add("未知的地产档位：" + (t == null ? "空" : t.tier()));
                    continue;
                }
                if (seen.put(t.tier(), true) != null) {
                    errors.add(label + "地产重复");
                }
                amount(errors, label + "购买价", t.basePrice());
                amount(errors, label + "升级费", t.upgradeCost());
                if (t.rents() == null || t.rents().size() != 4) {
                    errors.add(label + "租金必须填写四个等级（未升级、一级、二级、三级）");
                } else {
                    for (int i = 0; i < 4; i++) {
                        Long r = t.rents().get(i);
                        if (r == null) {
                            errors.add(label + "租金第 " + (i + 1) + " 项为空");
                        } else {
                            amount(errors, label + LEVELS[i] + "租金", r);
                        }
                    }
                }
            }
        }
        if (s.station() == null) {
            errors.add("车站配置为空");
        } else {
            amount(errors, "车站购买价", s.station().price());
            amount(errors, "车站每座租金", s.station().rentPerStation());
        }
        if (s.fees() == null) {
            errors.add("固定费用配置为空");
        } else {
            amount(errors, "经过起点奖励", s.fees().startReward());
            amount(errors, "小游戏获胜奖励", s.fees().miniGameWinReward());
            amount(errors, "出狱费用", s.fees().bailCost());
        }
        if (s.eventCash() == null) {
            errors.add("事件金额配置为空");
        } else {
            EventCash c = s.eventCash();
            amount(errors, "事件金额最小值", c.min());
            amount(errors, "事件金额最大值", c.max());
            amount(errors, "事件金额步长", c.step());
            if (c.min() > c.max()) {
                errors.add("事件金额最小值不能大于最大值");
            } else if (c.step() > 0 && (c.max() - c.min()) % c.step() != 0) {
                errors.add("事件金额步长必须能整除（最大值 − 最小值）");
            }
        }
        weights(errors, "事件概率", s.eventWeights(), EventKind.values(), 100);
        weights(errors, "道具概率", s.cardWeights(), CardType.values(), 1000);
        names(errors, s.tileNames());
        lucky(errors, s.lucky() == null ? defaultLucky() : s.lucky());
        rentRise(errors, rentRiseOf(s));
        room(errors, roomOf(s));
        int hand = handLimitOf(s);
        if (hand < BASE.economy().initialHandSize() || hand > MAX_HAND_LIMIT) {
            errors.add("道具上限必须在 " + BASE.economy().initialHandSize() + "～" + MAX_HAND_LIMIT + " 张之间");
        }
    }

    /** 每组选项最多几个：建房页一行放得下的数量。 */
    static final int MAX_ROOM_CHOICES = 5;

    private static void room(List<String> errors, RoomSetting r) {
        choices(errors, "初始现金", r.initialCashOptions(), r.defaultInitialCash(), 1, MAX_PRICE, "");
        choices(errors, "限时时长", r.timeLimitMinutesOptions(), r.defaultTimeLimitMinutes(), 5, 240, " 分钟");
        choices(errors, "投骰时间", r.rollSecondsOptions(), r.defaultRollSeconds(), 5, 120, " 秒");
        if (!"TIME_LIMIT".equals(r.defaultEndMode()) && !"BANKRUPTCY".equals(r.defaultEndMode())) {
            errors.add("默认结束模式只能是限时或破产");
        }
        if (r.bankruptcyCapMinutes() < 10 || r.bankruptcyCapMinutes() > 600) {
            errors.add("破产模式最长时长必须在 10～600 分钟之间");
        }
    }

    private static <T extends Number> void choices(List<String> errors, String label, List<T> options, long dflt,
                                                   long min, long max, String unit) {
        if (options == null || options.isEmpty() || options.size() > MAX_ROOM_CHOICES) {
            errors.add(label + "选项需要 1～" + MAX_ROOM_CHOICES + " 个");
            return;
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (T o : options) {
            if (o == null || o.longValue() < min || o.longValue() > max) {
                errors.add(label + "选项必须在 " + min + "～" + max + unit + " 之间");
                return;
            }
            if (!seen.add(o.longValue())) {
                errors.add(label + "选项有重复：" + o + unit);
            }
        }
        if (!seen.contains(dflt)) {
            errors.add(label + "默认值必须是其中一个选项");
        }
    }

    private static void rentRise(List<String> errors, RentRise r) {
        if (r.freeRounds() < 0 || r.freeRounds() > 1000) {
            errors.add("租金上涨：原价轮数必须在 0～1000 之间");
        }
        if (r.everyRounds() < 1 || r.everyRounds() > 100) {
            errors.add("租金上涨：上涨间隔必须在 1～100 轮之间");
        }
        if (r.stepPercent() < 0 || r.stepPercent() > 100) {
            errors.add("租金上涨：每次涨幅必须在 0～100% 之间（0 表示不上涨）");
        }
        if (r.capPercent() < 100 || r.capPercent() > 1000) {
            errors.add("租金上涨：封顶倍率必须在 100%～1000% 之间");
        }
    }

    /** 幸运 / 不幸奖池：种类与默认一致（不增删、不换序），权重 0～100 且每个奖池合计大于 0，奖励 / 罚款金额与事件金额同样上限。 */
    private static void lucky(List<String> errors, List<LuckySetting> lucky) {
        List<LuckySetting> base = defaultLucky();
        if (lucky.size() != base.size()) {
            errors.add("幸运 / 不幸奖池必须是 " + base.size() + " 项");
            return;
        }
        int total = 0;
        int unluckyTotal = 0;
        for (int i = 0; i < base.size(); i++) {
            LuckySetting l = lucky.get(i);
            LuckySetting b = base.get(i);
            String name = (b.unlucky() ? "不幸" : "幸运") + "「" + b.label() + "」";
            if (l == null || !b.kind().equals(l.kind()) || l.unlucky() != b.unlucky()) {
                errors.add("幸运 / 不幸奖池第 " + (i + 1) + " 项应为" + name);
                continue;
            }
            if (l.weight() < 0 || l.weight() > 100) {
                errors.add(name + "概率必须在 0～100 之间");
            } else if (b.unlucky()) {
                unluckyTotal += l.weight();
            } else {
                total += l.weight();
            }
            if (EventKind.CASH_REWARD.name().equals(b.kind()) || EventKind.CASH_FINE.name().equals(b.kind())) {
                amount(errors, name + "金额", l.amount());
            }
        }
        if (total <= 0) {
            errors.add("幸运奖池的概率不能全为 0");
        }
        if (unluckyTotal <= 0) {
            errors.add("不幸奖池的概率不能全为 0");
        }
    }

    private static final String[] LEVELS = {"未升级", "一级", "二级", "三级"};

    private static void amount(List<String> errors, String label, long v) {
        if (v <= 0) {
            errors.add(label + "必须大于 0");
        } else if (v > MAX_PRICE) {
            errors.add(label + "不能超过 " + MAX_PRICE);
        }
    }

    private static <E extends Enum<E>> void weights(List<String> errors, String label, Map<String, Integer> w,
                                                     E[] keys, int total) {
        if (w == null) {
            errors.add(label + "为空");
            return;
        }
        long sum = 0;
        for (E k : keys) {
            Integer v = w.get(k.name());
            if (v == null) {
                errors.add(label + "缺少 " + k.name());
            } else if (v < 0) {
                errors.add(label + " " + k.name() + " 不能为负数");
            } else {
                sum += v;
            }
        }
        for (String k : w.keySet()) {
            if (java.util.Arrays.stream(keys).noneMatch(e -> e.name().equals(k))) {
                errors.add(label + "含未知项：" + k);
            }
        }
        if (sum != total) {
            errors.add(label + "合计必须为 " + total + "（当前 " + sum + "）");
        }
    }

    private static void names(List<String> errors, Map<String, List<String>> names) {
        if (names == null) {
            errors.add("地名为空");
            return;
        }
        for (BoardTemplate b : BASE.boards()) {
            List<String> list = names.get(b.id());
            if (list == null || list.size() != b.size()) {
                errors.add("地图 " + b.id() + " 必须为全部 " + b.size() + " 个格子填写名称");
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                String n = list.get(i) == null ? "" : list.get(i).strip();
                if (n.isEmpty()) {
                    errors.add("地图 " + b.id() + " 第 " + i + " 格名称为空");
                } else if (n.codePointCount(0, n.length()) > MAX_NAME_LENGTH) {
                    errors.add("地图 " + b.id() + " 第 " + i + " 格名称超过 " + MAX_NAME_LENGTH + " 个字");
                } else if (n.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                        || Character.getType(cp) == Character.FORMAT || cp == '<' || cp == '>')) {
                    errors.add("地图 " + b.id() + " 第 " + i + " 格名称含不允许的字符");
                }
            }
        }
        for (String id : names.keySet()) {
            if (BASE.board(id).isEmpty()) {
                errors.add("未知的地图：" + id);
            }
        }
    }

    private static String tierLabel(String tier) {
        if (tier == null) {
            return null;
        }
        return switch (tier) {
            case "LOW" -> "低价";
            case "MID" -> "中价";
            case "HIGH" -> "高价";
            default -> null;
        };
    }

    /** 名称去掉首尾空白后的规范形状（发布时保存这一份）。 */
    public static GameSettings normalized(GameSettings s) {
        Map<String, List<String>> names = new TreeMap<>();
        s.tileNames().forEach((k, v) -> names.put(k, v.stream().map(String::strip).toList()));
        List<TierSetting> tiers = new ArrayList<>(s.tiers());
        tiers.sort(java.util.Comparator.comparingInt(t -> Tier.valueOf(t.tier()).ordinal()));
        Map<String, Integer> events = new LinkedHashMap<>();
        for (EventKind k : EventKind.values()) {
            events.put(k.name(), s.eventWeights().get(k.name()));
        }
        Map<String, Integer> cards = new LinkedHashMap<>();
        for (CardType k : CardType.values()) {
            cards.put(k.name(), s.cardWeights().get(k.name()));
        }
        List<LuckySetting> lucky = new ArrayList<>();
        List<LuckySetting> base = defaultLucky();
        List<LuckySetting> given = luckyOf(s);
        for (int i = 0; i < base.size(); i++) {
            LuckySetting b = base.get(i);
            LuckySetting g = given.get(i);
            boolean cash = EventKind.CASH_REWARD.name().equals(b.kind()) || EventKind.CASH_FINE.name().equals(b.kind());
            lucky.add(new LuckySetting(b.kind(), b.label(), cash ? g.amount() : 0, g.weight(), b.unlucky()));
        }
        return new GameSettings(List.copyOf(tiers), s.station(), s.fees(), s.eventCash(), events, cards, names,
                List.copyOf(lucky), rentRiseOf(s), handLimitOf(s), sortedRoom(roomOf(s)));
    }

    /** 选项按从小到大保存（建房页按这个顺序显示）。 */
    private static RoomSetting sortedRoom(RoomSetting r) {
        return new RoomSetting(r.initialCashOptions().stream().sorted().toList(), r.defaultInitialCash(),
                r.timeLimitMinutesOptions().stream().sorted().toList(), r.defaultTimeLimitMinutes(),
                r.rollSecondsOptions().stream().sorted().toList(), r.defaultRollSeconds(),
                r.defaultEndMode(), r.bankruptcyCapMinutes());
    }

    /** 调用前须先通过 {@link #validate} 的结构检查。 */
    public static RuleConfig toRuleConfig(GameSettings s) {
        Map<Tier, TierSetting> byTier = new EnumMap<>(Tier.class);
        for (TierSetting t : s.tiers()) {
            byTier.put(Tier.valueOf(t.tier()), t);
        }
        List<TierPricing> tiers = new ArrayList<>();
        for (TierPricing base : BASE.tiers()) {
            TierSetting t = byTier.get(base.tier());
            tiers.add(new TierPricing(base.tier(), t.basePrice(), t.upgradeCost(), List.copyOf(t.rents()),
                    base.emergencyMortgage()));
        }
        StationPricing st = new StationPricing(s.station().price(), s.station().rentPerStation(),
                BASE.station().emergencyMortgage());
        EconomyConfig e = BASE.economy();
        EconomyConfig economy = new EconomyConfig(s.fees().startReward(), s.fees().miniGameWinReward(),
                s.fees().bailCost(), s.eventCash().min(), s.eventCash().max(), s.eventCash().step(),
                e.eventMoveMinSteps(), e.eventMoveMaxSteps(), e.dieFaces(), e.maxLevel(), handLimitOf(s),
                e.initialHandSize(), e.orderNumberMax(), e.offerUnaffordablePurchase(), e.upgradeAfterPurchase(),
                e.cardsEnabled());
        Map<CardType, Integer> cards = new TreeMap<>();
        for (CardType k : CardType.values()) {
            cards.put(k, s.cardWeights().get(k.name()));
        }
        Map<EventKind, Integer> events = new TreeMap<>();
        for (EventKind k : EventKind.values()) {
            events.put(k, s.eventWeights().get(k.name()));
        }
        List<FixedEvent> pool = new ArrayList<>();
        for (LuckySetting l : luckyOf(s)) {
            if (l.weight() > 0) { // 概率为 0 的项不进奖池（引擎要求权重为正）
                pool.add(new FixedEvent(EventKind.valueOf(l.kind()), l.amount(), l.label(), l.weight(), l.unlucky()));
            }
        }
        List<BoardTemplate> boards = new ArrayList<>();
        for (BoardTemplate b : BASE.boards()) {
            boolean hasLucky = b.count(TileType.FIXED_EVENT) > 0;
            boolean hasUnlucky = b.count(TileType.UNLUCKY_EVENT) > 0;
            boards.add(new BoardTemplate(b.id(), b.minPlayers(), b.maxPlayers(), b.tiles(),
                    pool.stream().filter(f -> f.unlucky() ? hasUnlucky : hasLucky).toList()));
        }
        RentRise rr = rentRiseOf(s);
        RoomSetting rs = sortedRoom(roomOf(s));
        RoomOptions base = BASE.room();
        RoomOptions room = new RoomOptions(base.minPlayersToStart(), rs.initialCashOptions(), base.defaultBoardId(),
                rs.defaultInitialCash(), EndMode.valueOf(rs.defaultEndMode()), rs.defaultTimeLimitMinutes(), rs.defaultRollSeconds());
        var timing = BASE.timing().withRoomChoices(rs.rollSecondsOptions(), rs.timeLimitMinutesOptions(), rs.bankruptcyCapMinutes());
        return new RuleConfig(BASE.ruleVersion(), boards, tiers, st, economy, BASE.ratios(), cards, events,
                timing, room, new RentInflation(rr.freeRounds(), rr.everyRounds(), rr.stepPercent(), rr.capPercent()));
    }

    private static Map<String, List<String>> loadDefaultNames() {
        try (InputStream in = SettingsMapper.class.getResourceAsStream("/config/default-tile-names.json")) {
            if (in == null) {
                throw new IllegalStateException("missing /config/default-tile-names.json");
            }
            Map<String, List<String>> m = new ObjectMapper().readValue(in, new TypeReference<>() {
            });
            return java.util.Collections.unmodifiableMap(new TreeMap<>(m));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read default tile names", e);
        }
    }
}
