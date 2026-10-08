package com.millionnaire.engine.config;

import com.millionnaire.engine.serialize.Canonical;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 不可变规则配置。对局创建时记录 {@link #contentHash()}，续跑时必须一致（旧局不读新配置）。
 * 权重：道具按千分比合计 1000，事件按百分比合计 100。
 */
public record RuleConfig(
        String ruleVersion,
        List<BoardTemplate> boards,
        List<TierPricing> tiers,
        StationPricing station,
        EconomyConfig economy,
        RatioConfig ratios,
        Map<CardType, Integer> cardWeights,
        Map<EventKind, Integer> eventWeights,
        TimingConfig timing,
        RoomOptions room,
        RentInflation rentInflation,
        SetBonus setBonus) {

    /** 不指定同组加成时没有加成（{@link SetBonus#NONE}）。 */
    public RuleConfig(String ruleVersion, List<BoardTemplate> boards, List<TierPricing> tiers, StationPricing station,
                      EconomyConfig economy, RatioConfig ratios, Map<CardType, Integer> cardWeights,
                      Map<EventKind, Integer> eventWeights, TimingConfig timing, RoomOptions room, RentInflation rentInflation) {
        this(ruleVersion, boards, tiers, station, economy, ratios, cardWeights, eventWeights, timing, room, rentInflation, SetBonus.NONE);
    }

    /** 不指定租金上涨参数时用默认值 {@link RentInflation#DEFAULT}。 */
    public RuleConfig(String ruleVersion, List<BoardTemplate> boards, List<TierPricing> tiers, StationPricing station,
                      EconomyConfig economy, RatioConfig ratios, Map<CardType, Integer> cardWeights,
                      Map<EventKind, Integer> eventWeights, TimingConfig timing, RoomOptions room) {
        this(ruleVersion, boards, tiers, station, economy, ratios, cardWeights, eventWeights, timing, room, RentInflation.DEFAULT);
    }

    public RuleConfig {
        rentInflation = rentInflation == null ? RentInflation.DEFAULT : rentInflation;
        setBonus = setBonus == null ? SetBonus.NONE : setBonus;
        boards = Immutable.list(boards);
        tiers = Immutable.list(tiers);
        cardWeights = Immutable.sortedMap(cardWeights);
        eventWeights = Immutable.sortedMap(eventWeights);
    }

    /**
     * 规范序列化后的 SHA-256（小写十六进制）。这是结构哈希而非语义哈希：比例 50/100 与 1/2、
     * 列表换序都会得到不同哈希；保守地视为不同配置（拒绝续跑）是安全的。
     */
    public String contentHash() {
        return Canonical.sha256Hex(this);
    }

    public Optional<BoardTemplate> board(String id) {
        return boards.stream().filter(b -> b.id().equals(id)).findFirst();
    }

    public TierPricing tier(Tier tier) {
        return tiers.stream().filter(t -> t.tier() == tier).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no pricing for " + tier));
    }
}
