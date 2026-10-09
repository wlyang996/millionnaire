package com.millionnaire.engine.config;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** 房间可选项与默认设置。 */
public record RoomOptions(
        int minPlayersToStart,
        List<Long> initialCashOptions,
        String defaultBoardId,
        long defaultInitialCash,
        EndMode defaultEndMode,
        int defaultTimeLimitMinutes,
        int defaultRollSeconds, boolean fastModeEnabled, boolean defaultFastMode, int fastAnimationPercent) {
    public RoomOptions(int minPlayersToStart, List<Long> initialCashOptions, String defaultBoardId, long defaultInitialCash,
                       EndMode defaultEndMode, int defaultTimeLimitMinutes, int defaultRollSeconds) {
        this(minPlayersToStart, initialCashOptions, defaultBoardId, defaultInitialCash, defaultEndMode,
                defaultTimeLimitMinutes, defaultRollSeconds, true, false, 50);
    }
    public RoomOptions {
        initialCashOptions = Immutable.list(initialCashOptions);
    }
}
