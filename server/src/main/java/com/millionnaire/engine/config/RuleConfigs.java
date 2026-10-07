package com.millionnaire.engine.config;

import com.millionnaire.engine.money.Ratio;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 默认规则配置 v1：数值取自 requirements.md 与 open-decisions.md；棋盘为 opus-analysis 4.10 草案（待策划复核）。
 */
public final class RuleConfigs {
    public static final String RULE_VERSION = RulesV1Spec.RULE_VERSION;
    public static final String BOARD_30 = "classic-30";
    public static final String BOARD_50 = "classic-50";

    /** 布局与美术设计稿 design/ui/board-v6（客户端 BoardNames.ts 的 DESIGN_30/50）逐格一致。
     * 记号：S 起点，L/M/H 普通地产（* = 指定拍卖地），E 事件，T 车站，B 银行，J 监狱，R 休息，G 游戏区。 */
    public static final String LAYOUT_30 =
            "S L E M T H L J M E L T H* M* B G L* E M T H L R M E L T M H E";
    public static final String LAYOUT_50 =
            "S M E L T H B L M* E H J M E L* T H L E M H* T M E L G H L E T "
            + "M* B H E M L* R H E L T M H E G M L T L M";

    private RuleConfigs() {
    }

    /** 正式配置：rules-v1 数值 + 美术设计稿布局。 */
    public static RuleConfig defaultV1() {
        return v1(LAYOUT_30, LAYOUT_50, true);
    }

    /** rules-v1 数值 + 指定的 30 / 50 格布局（测试夹具可用别的合规布局）；不启用正式服的对局策略。 */
    public static RuleConfig v1(String layout30, String layout50) {
        return v1(layout30, layout50, false);
    }

    /**
     * @param production 正式服的对局策略：现金不足也开购买窗口（只能放弃）；买下后不能在同一次落点立即升级；
     *                   存活玩家全部暂离 / 托管时直接结束对局
     */
    public static RuleConfig v1(String layout30, String layout50, boolean production) {
        Map<CardType, Integer> cards = new TreeMap<>();
        cards.put(CardType.ROADBLOCK, 120);
        cards.put(CardType.RENT_WAIVER, 120);
        cards.put(CardType.BUILD, 120);
        cards.put(CardType.DOWNGRADE, 120);
        cards.put(CardType.FIXED_MOVE, 120);
        cards.put(CardType.JAIL_RELEASE, 100);
        cards.put(CardType.AUCTION, 50);
        cards.put(CardType.TRADE, 50);
        cards.put(CardType.REFUSE_PURCHASE, 50);
        cards.put(CardType.HOUSE_PROTECTION, 50);
        cards.put(CardType.QUERY, 50);
        cards.put(CardType.FORCED_PURCHASE, 20);
        cards.put(CardType.DEMOLISH, 15);
        cards.put(CardType.CLEAR_LAND, 15);

        Map<EventKind, Integer> events = new TreeMap<>();
        events.put(EventKind.CASH_REWARD, 30);
        events.put(EventKind.CASH_FINE, 25);
        events.put(EventKind.CARD, 25);
        events.put(EventKind.MOVE, 15);
        events.put(EventKind.JAIL, 5);

        return new RuleConfig(
                RULE_VERSION,
                List.of(board(BOARD_30, 2, 4, layout30), board(BOARD_50, 2, 8, layout50)),
                List.of(
                        new TierPricing(Tier.LOW, 500, 300, List.of(100L, 250L, 450L, 700L), Ratio.percent(80)),
                        new TierPricing(Tier.MID, 1000, 600, List.of(200L, 500L, 900L, 1400L), Ratio.percent(70)),
                        new TierPricing(Tier.HIGH, 1500, 900, List.of(300L, 750L, 1350L, 2100L), Ratio.percent(60))),
                new StationPricing(1000, 200, Ratio.percent(70)),
                new EconomyConfig(1000, 500, 500, 100, 500, 50, 1, 3, 6, 3, 6, 2, 100, production, !production, production),
                new RatioConfig(
                        Ratio.percent(50),   // 标准价值计入升级费 50%
                        Ratio.percent(100),  // 银行抵押：原价 100%
                        Ratio.percent(10),   // 非银行赎回手续费
                        Ratio.percent(50),   // 起拍
                        Ratio.percent(10),   // 最小加价
                        Ratio.percent(250),  // 封顶 / 一口价
                        Ratio.percent(150),  // 强购
                        Ratio.percent(50),   // 交易下限（PO#12）
                        Ratio.percent(250),  // 交易上限
                        Ratio.percent(10)),  // 系统拍卖发起人分成
                cards,
                events,
                new TimingConfig(
                        List.of(15, 30, 45, 60),
                        List.of(15, 30, 60),
                        120,
                        15_000, 10_000, 15_000, 15_000, 10_000,
                        20_000, 3_000, 40_000, 30_000,
                        5_000, 15_000, 30_000, 120_000,
                        600_000, 30_000,
                        1_500, 250, 1_000,      // 动画与自动动作延时：占位值，待确认
                        production),
                // 默认地图、默认初始现金、默认结束模式尚未裁定，此处为占位（见 m0-report 待确认）
                new RoomOptions(2, List.of(2000L, 3000L, 5000L), BOARD_30, 3000, EndMode.TIME_LIMIT, 30, 15));
    }

    /** 由紧凑记号生成棋盘。 */
    public static BoardTemplate board(String id, int minPlayers, int maxPlayers, String layout) {
        String[] tokens = layout.trim().split("\\s+");
        List<Tile> tiles = new ArrayList<>(tokens.length);
        for (int i = 0; i < tokens.length; i++) {
            tiles.add(tile(i, tokens[i]));
        }
        return new BoardTemplate(id, minPlayers, maxPlayers, tiles);
    }

    private static Tile tile(int index, String token) {
        boolean designated = token.endsWith("*");
        String code = designated ? token.substring(0, token.length() - 1) : token;
        return switch (code) {
            case "S" -> new Tile(index, TileType.START, null, designated);
            case "L" -> new Tile(index, TileType.PROPERTY, Tier.LOW, designated);
            case "M" -> new Tile(index, TileType.PROPERTY, Tier.MID, designated);
            case "H" -> new Tile(index, TileType.PROPERTY, Tier.HIGH, designated);
            case "E" -> new Tile(index, TileType.EVENT, null, designated);
            case "T" -> new Tile(index, TileType.STATION, null, designated);
            case "B" -> new Tile(index, TileType.BANK, null, designated);
            case "J" -> new Tile(index, TileType.JAIL, null, designated);
            case "R" -> new Tile(index, TileType.REST, null, designated);
            case "G" -> new Tile(index, TileType.GAME_ZONE, null, designated);
            default -> throw new IllegalArgumentException("unknown tile code: " + token);
        };
    }
}
