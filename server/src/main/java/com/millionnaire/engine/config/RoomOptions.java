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
        int defaultRollSeconds) {
    public RoomOptions {
        initialCashOptions = Immutable.list(initialCashOptions);
    }
}
