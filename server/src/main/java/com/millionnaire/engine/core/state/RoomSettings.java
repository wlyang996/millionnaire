package com.millionnaire.engine.core.state;

import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RuleConfig;

/** 房主可修改的开局设置（requirements 第 2 节）。 */
public record RoomSettings(String boardId, long initialCash, EndMode endMode, int timeLimitMinutes, int rollSeconds) {

    public static RoomSettings defaults(RuleConfig config) {
        var r = config.room();
        return new RoomSettings(r.defaultBoardId(), r.defaultInitialCash(), r.defaultEndMode(),
                r.defaultTimeLimitMinutes(), r.defaultRollSeconds());
    }
}
