package com.millionnaire.engine.core.state;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** 对局结算摘要：回大厅后保留在会话中，供大厅视图与结算期间重连。 */
public record GameResult(long gameNo, String reason, List<Standing> standings) {
    public GameResult {
        standings = Immutable.list(standings);
    }
}
