package com.millionnaire.engine.config;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** 计时参数（毫秒，档位为秒/分钟）。 */
public record TimingConfig(
        List<Integer> rollSecondsOptions,
        List<Integer> timeLimitMinutesOptions,
        int bankruptcyModeCapMinutes,
        long decisionWindowMs,
        long responseWindowMs,
        long discardWindowMs,
        long tradeResponseMs,
        long toothPickMs,
        long auctionDurationMs,
        long auctionExtendMs,
        long auctionMaxMs,
        long debtSegmentMs,
        long heartbeatMs,
        long suspectAfterMs,
        long offlineAfterMs,
        long allOfflineCloseMs,
        long downtimeBudgetMs,
        long recoveryPrepMs) {
    public TimingConfig {
        rollSecondsOptions = Immutable.list(rollSecondsOptions);
        timeLimitMinutesOptions = Immutable.list(timeLimitMinutesOptions);
    }
}
