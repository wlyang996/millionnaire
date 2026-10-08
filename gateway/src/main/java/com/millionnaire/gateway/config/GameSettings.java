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
        RentRise rentRise) {

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
