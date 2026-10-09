package com.millionnaire.gateway.config;

import java.util.List;
import java.util.Map;

/**
 * 管理后台可调整的游戏参数（发布快照的 JSON 形状）。其余规则（地图格子类型与顺序、计时、比例、房间选项）
 * 不开放调整，沿用引擎默认配置 {@code RuleConfigs.defaultV1()}。
 *
 * @param tiers       普通地产三档（LOW / MID / HIGH）：购买价、每级升级费、未升级/一级/二级/三级租金
 * @param station     车站：购买价、每座未抵押车站对应的租金
 * @param fees        固定金额：经过起点奖励、小游戏获胜奖励、出狱费用
 * @param eventCash   事件格现金奖励与罚款共用的金额范围（最小值～最大值，按步长等概率取值）
 * @param eventWeights 事件格抽到各类结果的权重（百分比，合计 100）
 * @param cardWeights  抽到各种道具的权重（千分比，合计 1000）
 * @param tileNames    地图 ID → 逐格名称（下标 = 格子序号）
 * @param lucky        幸运 / 不幸格奖池（两张地图共用；unlucky=true 为不幸）：每项的权重与金额（只有奖励 / 罚款有金额）；
 *                     种类与卡名不开放修改。
 *                     早于 2026-10-08 发布的版本没有此项（null），按内置默认处理。
 * @param rentRise     破产模式租金随轮数上涨：前 freeRounds 轮原价，之后每 everyRounds 轮倍率 +stepPercent%，
 *                     封顶 capPercent%（stepPercent = 0 关闭）。没有此项（null）按内置默认处理。
 * @param handLimit    每人最多持有的道具张数（超出时弃牌）；房间"开局道具"的可选张数也以此为上限。没有此项（null）按内置默认处理。
 * @param room         建房可选项：初始现金、限时时长、投骰时间各自的选项与默认值，默认结束模式，破产模式最长时长（分钟）。
 *                     没有此项（null）按内置默认处理。
 * @param sets         同组地产加成：倍率（%，100 = 关闭）与地图 ID → 逐格组号（0 = 不分组，只给普通地产编组，每组至少 2 块）。
 *                     没有此项（null）按内置默认处理。
 * @param timing       操作时限（秒）与动画缓冲（毫秒）。没有此项（null）按内置默认处理。
 * @param announcement 大厅公告（开关、标题、内容），随发布生效。没有此项（null）为不显示。
 * @param startPick    前进经过或停在起点时的三选一（抽完继续剩余步数）：现金 / 道具权重与现金范围。null按内置默认处理。
 */
public record GameSettings(
        List<TierSetting> tiers,
        StationSetting station,
        Fees fees,
        EventCash eventCash,
        Map<String, Integer> eventWeights,
        Map<String, Integer> cardWeights,
        Map<String, List<String>> tileNames,
        List<LuckySetting> lucky,
        RentRise rentRise,
        Integer handLimit,
        RoomSetting room,
        SetSetting sets,
        TimingSetting timing,
        Announcement announcement,
        StartPickSetting startPick) {

    public GameSettings(List<TierSetting> tiers, StationSetting station, Fees fees, EventCash eventCash,
                        Map<String, Integer> eventWeights, Map<String, Integer> cardWeights,
                        Map<String, List<String>> tileNames, List<LuckySetting> lucky, RentRise rentRise,
                        Integer handLimit, RoomSetting room, SetSetting sets, TimingSetting timing, Announcement announcement) {
        this(tiers, station, fees, eventCash, eventWeights, cardWeights, tileNames, lucky, rentRise, handLimit, room, sets, timing,
                announcement, null);
    }

    public GameSettings(List<TierSetting> tiers, StationSetting station, Fees fees, EventCash eventCash,
                        Map<String, Integer> eventWeights, Map<String, Integer> cardWeights,
                        Map<String, List<String>> tileNames, List<LuckySetting> lucky, RentRise rentRise,
                        Integer handLimit, RoomSetting room, SetSetting sets) {
        this(tiers, station, fees, eventCash, eventWeights, cardWeights, tileNames, lucky, rentRise, handLimit, room, sets, null, null);
    }

    public GameSettings(List<TierSetting> tiers, StationSetting station, Fees fees, EventCash eventCash,
                        Map<String, Integer> eventWeights, Map<String, Integer> cardWeights,
                        Map<String, List<String>> tileNames, List<LuckySetting> lucky, RentRise rentRise,
                        Integer handLimit, RoomSetting room) {
        this(tiers, station, fees, eventCash, eventWeights, cardWeights, tileNames, lucky, rentRise, handLimit, room, null, null, null);
    }

    public GameSettings(List<TierSetting> tiers, StationSetting station, Fees fees, EventCash eventCash,
                        Map<String, Integer> eventWeights, Map<String, Integer> cardWeights,
                        Map<String, List<String>> tileNames, List<LuckySetting> lucky, RentRise rentRise,
                        Integer handLimit) {
        this(tiers, station, fees, eventCash, eventWeights, cardWeights, tileNames, lucky, rentRise, handLimit, null, null, null, null);
    }

    public GameSettings(List<TierSetting> tiers, StationSetting station, Fees fees, EventCash eventCash,
                        Map<String, Integer> eventWeights, Map<String, Integer> cardWeights,
                        Map<String, List<String>> tileNames, List<LuckySetting> lucky, RentRise rentRise) {
        this(tiers, station, fees, eventCash, eventWeights, cardWeights, tileNames, lucky, rentRise, null, null, null, null, null);
    }

    public GameSettings(List<TierSetting> tiers, StationSetting station, Fees fees, EventCash eventCash,
                        Map<String, Integer> eventWeights, Map<String, Integer> cardWeights,
                        Map<String, List<String>> tileNames, List<LuckySetting> lucky) {
        this(tiers, station, fees, eventCash, eventWeights, cardWeights, tileNames, lucky, null);
    }

    /** 旧版本快照（没有幸运奖池）的兼容构造。 */
    public GameSettings(List<TierSetting> tiers, StationSetting station, Fees fees, EventCash eventCash,
                        Map<String, Integer> eventWeights, Map<String, Integer> cardWeights,
                        Map<String, List<String>> tileNames) {
        this(tiers, station, fees, eventCash, eventWeights, cardWeights, tileNames, null, null);
    }

    /**
     * 操作时限：decision 买地 / 升级 / 银行等选择，response 免租等响应卡询问，discard 弃牌，trade 交易回应，tooth 拔牙每次选择，
     * auction 拍卖时长 / 最后几秒出价顺延 / 最长，debtSegment 欠款每段（共两段）；单位秒。
     * animDiceMs / animPerStepMs 为投骰与每走一格留给动画的时间，autoActDelayMs 为托管、掉线时系统代为操作前的等待；单位毫秒。
     */
    public record TimingSetting(int decisionSeconds, int responseSeconds, int discardSeconds, int tradeSeconds,
                                int toothSeconds, int auctionSeconds, int auctionExtendSeconds, int auctionMaxSeconds,
                                int debtSegmentSeconds, int animDiceMs, int animPerStepMs, int autoActDelayMs, Integer allAwayTurns, Integer eventPresentationMs, Integer eventCashPresentationMs, Integer startPickPresentationMs, Integer jailPresentationMs) {
        public TimingSetting(int decisionSeconds, int responseSeconds, int discardSeconds, int tradeSeconds,
                             int toothSeconds, int auctionSeconds, int auctionExtendSeconds, int auctionMaxSeconds,
                             int debtSegmentSeconds, int animDiceMs, int animPerStepMs, int autoActDelayMs, Integer allAwayTurns) {
            this(decisionSeconds, responseSeconds, discardSeconds, tradeSeconds, toothSeconds, auctionSeconds, auctionExtendSeconds,
                    auctionMaxSeconds, debtSegmentSeconds, animDiceMs, animPerStepMs, autoActDelayMs, allAwayTurns, null, null, null, null);
        }
        public TimingSetting(int decisionSeconds, int responseSeconds, int discardSeconds, int tradeSeconds,
                             int toothSeconds, int auctionSeconds, int auctionExtendSeconds, int auctionMaxSeconds,
                             int debtSegmentSeconds, int animDiceMs, int animPerStepMs, int autoActDelayMs) {
            this(decisionSeconds, responseSeconds, discardSeconds, tradeSeconds, toothSeconds, auctionSeconds,
                    auctionExtendSeconds, auctionMaxSeconds, debtSegmentSeconds, animDiceMs, animPerStepMs, autoActDelayMs, null);
        }
    }

    /** 大厅公告：enabled 关闭时不显示；title 最多 20 字，text 最多 200 字。 */
    public record Announcement(boolean enabled, String title, String text) {
    }

    /** 起点三选一：每张牌按 cashWeight : cardWeight 决定是现金还是道具（都为 0 关闭）；现金在 cashMin～cashMax 间按步长等概率取值。 */
    public record StartPickSetting(int cashWeight, int cardWeight, long cashMin, long cashMax, long cashStep) {
    }

    public record SetSetting(int rentPercent, Map<String, List<Integer>> groups) {
    }

    public record RoomSetting(List<Long> initialCashOptions, long defaultInitialCash,
                              List<Integer> timeLimitMinutesOptions, int defaultTimeLimitMinutes,
                              List<Integer> rollSecondsOptions, int defaultRollSeconds,
                              String defaultEndMode, int bankruptcyCapMinutes, Boolean fastModeEnabled, Boolean defaultFastMode, Integer fastAnimationPercent) {
        public RoomSetting(List<Long> initialCashOptions, long defaultInitialCash, List<Integer> timeLimitMinutesOptions,
                           int defaultTimeLimitMinutes, List<Integer> rollSecondsOptions, int defaultRollSeconds,
                           String defaultEndMode, int bankruptcyCapMinutes) {
            this(initialCashOptions, defaultInitialCash, timeLimitMinutesOptions, defaultTimeLimitMinutes, rollSecondsOptions,
                    defaultRollSeconds, defaultEndMode, bankruptcyCapMinutes, null, null, null);
        }
    }

    public record RentRise(int freeRounds, int everyRounds, int stepPercent, int capPercent) {
    }

    public record LuckySetting(String kind, String label, long amount, int weight, boolean unlucky) {
    }

    public record TierSetting(String tier, long basePrice, long upgradeCost, List<Long> rents) {
    }

    public record StationSetting(long price, long rentPerStation) {
    }

    public record Fees(long startReward, long miniGameWinReward, long bailCost) {
    }

    public record EventCash(long min, long max, long step) {
    }
}
