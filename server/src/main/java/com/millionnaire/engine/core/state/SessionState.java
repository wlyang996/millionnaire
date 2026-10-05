package com.millionnaire.engine.core.state;

/**
 * 生产领域的唯一状态：一个房间会话 = 大厅（成员、房主、设置，跨局保留）+ 可选的进行中对局 + 上一局结算摘要。
 * game 为 null 表示在大厅；序号、随机状态与持久化链在大厅与对局之间连续，不重新创世。
 *
 * @param gamesPlayed  已结束的对局数；下一局编号为 gamesPlayed + 1（局号单调）
 * @param nextWindowId 会话内单调的窗口 ID 分配器（对局进行中由 game.flow 接管，回房时取回），跨局不复用
 * @param lastResult   上一局的结算摘要（中止局为 null）
 */
public record SessionState(RoomState lobby, GameState game, long gamesPlayed, long nextWindowId, GameResult lastResult)
        implements DomainState {

    public boolean inGame() {
        return game != null;
    }

    public SessionState withLobby(RoomState value) {
        return new SessionState(value, game, gamesPlayed, nextWindowId, lastResult);
    }

    public SessionState withGame(GameState value) {
        return new SessionState(lobby, value, gamesPlayed, nextWindowId, lastResult);
    }
}
