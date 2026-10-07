package com.millionnaire.engine.config;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * rules-v1 规格校验：requirements 第 2、3 节写死的结构约束（地图组成、容量、指定拍卖地、开局人数）。
 * 价格、权重、计时等数值属于"可调默认值"，只受通用校验约束，不在此锁定。
 */
public final class RulesV1Spec {
    public static final String RULE_VERSION = "rules-v1";

    private RulesV1Spec() {
    }

    /** 一张地图的规格。 */
    private record BoardSpec(String id, int size, int minPlayers, int maxPlayers, Map<TileType, Integer> counts,
                             Map<Tier, Integer> tiers, Map<Tier, Integer> designated) {
    }

    private static final List<BoardSpec> BOARDS = List.of(
            new BoardSpec(RuleConfigs.BOARD_30, 30, 2, 4,
                    counts(1, 16, 5, 1, 1, 1, 1, 4), tiers(6, 6, 4), tiers(1, 1, 1)),
            new BoardSpec(RuleConfigs.BOARD_50, 50, 2, 8,
                    counts(1, 28, 9, 2, 1, 1, 2, 6), tiers(10, 10, 8), tiers(2, 2, 1)));

    /** 开局抽数上限（requirements 第 4 节"1～100"）。 */
    public static final int ORDER_NUMBER_MAX = 100;

    /** 开局最少人数（requirements 第 2 节"至少 2 人"）。 */
    public static final int MIN_PLAYERS_TO_START = 2;

    public static List<String> validate(RuleConfig c) {
        List<String> errors = new ArrayList<>();
        if (c.boards().size() != BOARDS.size()) {
            errors.add("rules-v1 requires exactly " + BOARDS.size() + " boards, got " + c.boards().size());
        }
        for (BoardSpec spec : BOARDS) {
            Optional<BoardTemplate> found = c.board(spec.id());
            if (found.isEmpty()) {
                errors.add("rules-v1 board " + spec.id() + " missing");
                continue;
            }
            BoardTemplate b = found.get();
            String p = "rules-v1 board " + spec.id();
            if (b.size() != spec.size()) {
                errors.add(p + " must have " + spec.size() + " tiles, got " + b.size());
            }
            if (b.minPlayers() != spec.minPlayers() || b.maxPlayers() != spec.maxPlayers()) {
                errors.add(p + " capacity must be " + spec.minPlayers() + ".." + spec.maxPlayers());
            }
            for (TileType type : TileType.values()) {
                if (type == TileType.FIXED_EVENT) {
                    continue; // 固定事件格算在事件格数里（2026-10-08 拆分，格数不变）
                }
                long actual = b.count(type) + (type == TileType.EVENT ? b.count(TileType.FIXED_EVENT) : 0);
                if (actual != spec.counts().get(type)) {
                    errors.add(p + " must have " + spec.counts().get(type) + " " + type + ", got " + actual);
                }
            }
            for (Tier tier : Tier.values()) {
                long actual = b.tiles().stream().filter(t -> t.tier() == tier).count();
                if (actual != spec.tiers().get(tier)) {
                    errors.add(p + " must have " + spec.tiers().get(tier) + " " + tier + " properties, got " + actual);
                }
                long designated = b.tiles().stream().filter(t -> t.tier() == tier && t.auctionDesignated()).count();
                if (designated != spec.designated().get(tier)) {
                    errors.add(p + " must have " + spec.designated().get(tier) + " designated " + tier
                            + " auction properties, got " + designated);
                }
            }
        }
        if (c.room().minPlayersToStart() != MIN_PLAYERS_TO_START) {
            errors.add("rules-v1 room.minPlayersToStart must be " + MIN_PLAYERS_TO_START);
        }
        // requirements 第 4 节：开局数字为 1～100；更小的上限可能让同分重抽永不结束（T1）
        if (c.economy().orderNumberMax() != ORDER_NUMBER_MAX) {
            errors.add("rules-v1 economy.orderNumberMax must be " + ORDER_NUMBER_MAX);
        }
        return errors;
    }

    private static Map<TileType, Integer> counts(int start, int property, int event, int bank, int jail, int rest,
                                                 int game, int station) {
        Map<TileType, Integer> m = new EnumMap<>(TileType.class);
        m.put(TileType.START, start);
        m.put(TileType.PROPERTY, property);
        m.put(TileType.EVENT, event);
        m.put(TileType.BANK, bank);
        m.put(TileType.JAIL, jail);
        m.put(TileType.REST, rest);
        m.put(TileType.GAME_ZONE, game);
        m.put(TileType.STATION, station);
        return m;
    }

    private static Map<Tier, Integer> tiers(int low, int mid, int high) {
        Map<Tier, Integer> m = new EnumMap<>(Tier.class);
        m.put(Tier.LOW, low);
        m.put(Tier.MID, mid);
        m.put(Tier.HIGH, high);
        return m;
    }
}
