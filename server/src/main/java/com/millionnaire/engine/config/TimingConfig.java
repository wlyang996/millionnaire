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
}
