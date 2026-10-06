package com.millionnaire.engine.core.state;

import com.millionnaire.engine.core.command.SessionCommand.EndGame;
import com.millionnaire.engine.core.event.GameEvent.ControlChanged;

/**
 * 生产领域的唯一状态：一个房间会话 = 大厅（成员、房主、设置，跨局保留）+ 可选的进行中对局 + 上一局结算摘要。
 * game 为 null 表示在大厅；序号、随机状态与持久化链在大厅与对局之间连续，不重新创世。
 *
 * @param gamesPlayed  已结束的对局数；下一局编号为 gamesPlayed + 1（局号单调）
 * @param nextWindowId 会话内单调的窗口 ID 分配器（对局进行中由 game.flow 接管，回房时取回），跨局不复用
 * @param lastResult   上一局的结算摘要（中止局为 null）
 * @param abortSource  已接纳系统 EndGame 的本步凭据；GameAborted 消费，步边界必须为空
 * @param controlSource 已核对输入的本步控制凭据；紧随的 ControlChanged 消费，步边界必须为空
 */
public record SessionState(RoomState lobby, GameState game, long gamesPlayed, long nextWindowId, GameResult lastResult,
                           AbortSource abortSource, ControlChanged controlSource)
        implements DomainState {

    public record AbortSource(long inputSeq, EndGame command) {
    }

    /** Accepted input's one-use control credential; never survives a step boundary or restore. */
    public SessionState withControlSource(ControlChanged value) {
        return new SessionState(lobby, game, gamesPlayed, nextWindowId, lastResult, abortSource, value);
    }
    public SessionState(RoomState lobby, GameState game, long gamesPlayed, long nextWindowId, GameResult lastResult,
                        AbortSource abortSource) {
        this(lobby, game, gamesPlayed, nextWindowId, lastResult, abortSource, null);
    }
    public SessionState(RoomState lobby, GameState game, long gamesPlayed, long nextWindowId, GameResult lastResult) {
        this(lobby, game, gamesPlayed, nextWindowId, lastResult, null);
    }

    public boolean inGame() {
        return game != null;
    }

    public SessionState withLobby(RoomState value) {
        return new SessionState(value, game, gamesPlayed, nextWindowId, lastResult, abortSource, controlSource);
    }

    public SessionState withGame(GameState value) {
        return new SessionState(lobby, value, gamesPlayed, nextWindowId, lastResult, abortSource, controlSource);
    }

    public SessionState withAbortSource(AbortSource value) {
        return new SessionState(lobby, game, gamesPlayed, nextWindowId, lastResult, value, controlSource);
    }
}
