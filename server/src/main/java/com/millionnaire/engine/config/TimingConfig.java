package com.millionnaire.engine.config;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/**
 * 计时参数（毫秒，档位为秒/分钟）。animDiceMs / animPerStepMs 为动画缓冲（加在下一个窗口的 opensAt 之前，
 * 不占玩家操作时间），autoActDelayMs 为自动动作延时；这三个值尚未裁定，为占位默认值（见 m1b-report 待确认）。
 * endWhenAllAway：存活玩家全部暂离（挂机）/ 托管时，在回合交界直接结束对局（结束原因 ALL_AWAY）；只是掉线的不算。
 */
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
        long recoveryPrepMs,
        long animDiceMs,
        long animPerStepMs,
        long autoActDelayMs,
        boolean endWhenAllAway) {
    public TimingConfig {
        rollSecondsOptions = Immutable.list(rollSecondsOptions);
        timeLimitMinutesOptions = Immutable.list(timeLimitMinutesOptions);
    }

    /** 换掉各操作窗口时长与动画缓冲（管理后台「操作时限」可配，2026-10-08），心跳与掉线判定等不变。 */
    public TimingConfig withWindows(long decision, long response, long discard, long tradeResponse, long toothPick,
                                    long auctionDuration, long auctionExtend, long auctionMax, long debtSegment,
                                    long animDice, long animPerStep, long autoActDelay) {
        return new TimingConfig(rollSecondsOptions, timeLimitMinutesOptions, bankruptcyModeCapMinutes, decision, response,
                discard, tradeResponse, toothPick, auctionDuration, auctionExtend, auctionMax, debtSegment,
                heartbeatMs, suspectAfterMs, offlineAfterMs, allOfflineCloseMs, downtimeBudgetMs, recoveryPrepMs, animDice,
                animPerStep, autoActDelay, endWhenAllAway);
    }

    /** 换掉建房可选的投骰时间、限时档位与破产模式时长上限（管理后台可配），其余计时不变。 */
    public TimingConfig withRoomChoices(List<Integer> rollSeconds, List<Integer> timeLimitMinutes, int bankruptcyCapMinutes) {
        return new TimingConfig(rollSeconds, timeLimitMinutes, bankruptcyCapMinutes, decisionWindowMs, responseWindowMs,
                discardWindowMs, tradeResponseMs, toothPickMs, auctionDurationMs, auctionExtendMs, auctionMaxMs, debtSegmentMs,
                heartbeatMs, suspectAfterMs, offlineAfterMs, allOfflineCloseMs, downtimeBudgetMs, recoveryPrepMs, animDiceMs,
                animPerStepMs, autoActDelayMs, endWhenAllAway);
    }
}
