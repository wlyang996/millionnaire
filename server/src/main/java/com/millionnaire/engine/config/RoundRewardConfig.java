package com.millionnaire.engine.config;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** One automatic grant after a completed whole round, independent of rent inflation. */
public record RoundRewardConfig(boolean enabled, int timeLimitRewardRound, int bankruptcyRewardRound,
                                int rewardPercent, long perPlayerCap, int roundingUnit,
                                List<IncomeSource> incomeSources, int presentationMs, String name) {
    public RoundRewardConfig(boolean enabled, int timeLimitRewardRound, int bankruptcyRewardRound, int rewardPercent,
                             long perPlayerCap, int roundingUnit, List<IncomeSource> incomeSources, int presentationMs) {
        this(enabled, timeLimitRewardRound, bankruptcyRewardRound, rewardPercent, perPlayerCap, roundingUnit,
                incomeSources, presentationMs, "月度荣耀奖励");
    }
    public enum IncomeSource { RENT, START, EVENT, MINIGAME, COMMISSION }
    public static final RoundRewardConfig DEFAULT = new RoundRewardConfig(true, 5, 5, 5, 1000, 10,
            List.of(IncomeSource.values()), 3000);
    public static final RoundRewardConfig NONE = DEFAULT.withEnabled(false);
    public RoundRewardConfig {
        incomeSources = Immutable.list(incomeSources == null ? List.of() : incomeSources);
        name = name == null ? "月度荣耀奖励" : name;
    }
    public RoundRewardConfig withEnabled(boolean value) {
        return new RoundRewardConfig(value, timeLimitRewardRound, bankruptcyRewardRound, rewardPercent,
                perPlayerCap, roundingUnit, incomeSources, presentationMs, name);
    }
    public int round(EndMode mode) { return mode == EndMode.TIME_LIMIT ? timeLimitRewardRound : bankruptcyRewardRound; }
    public long reward(long income) {
        // Divide before multiplying the unbounded total; cap without overflowing on a very long game.
        long units = income / 100;
        long raw = rewardPercent == 0 ? 0 : units > perPlayerCap / rewardPercent ? perPlayerCap
                : Math.min(perPlayerCap, Math.addExact(units * rewardPercent, income % 100 * rewardPercent / 100));
        return raw / roundingUnit * roundingUnit;
    }
}
