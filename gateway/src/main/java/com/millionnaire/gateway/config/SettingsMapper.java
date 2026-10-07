package com.millionnaire.gateway.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.engine.config.BoardTemplate;
import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.ConfigValidator;
import com.millionnaire.engine.config.EconomyConfig;
import com.millionnaire.engine.config.EventKind;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.config.StationPricing;
import com.millionnaire.engine.config.Tier;
import com.millionnaire.engine.config.TierPricing;
import com.millionnaire.gateway.config.GameSettings.EventCash;
import com.millionnaire.gateway.config.GameSettings.Fees;
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
                events, cards, DEFAULT_NAMES);
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
        return new GameSettings(List.copyOf(tiers), s.station(), s.fees(), s.eventCash(), events, cards, names);
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
                e.eventMoveMinSteps(), e.eventMoveMaxSteps(), e.dieFaces(), e.maxLevel(), e.handLimit(),
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
        return new RuleConfig(BASE.ruleVersion(), BASE.boards(), tiers, st, economy, BASE.ratios(), cards, events,
                BASE.timing(), BASE.room());
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
