package com.millionnaire.engine.core.state;

/**
 * 生产领域的唯一状态：一个房间会话 = 大厅（成员、房主、设置，跨局保留）+ 可选的进行中对局。
 * game 为 null 表示在大厅；序号、随机状态与持久化链在大厅与对局之间连续，不重新创世。
 *
 * @param gamesPlayed 已结束的对局数；下一局编号为 gamesPlayed + 1
 */
public record SessionState(RoomState lobby, GameState game, long gamesPlayed) implements DomainState {

    public boolean inGame() {
        return game != null;
    }

    public SessionState withLobby(RoomState value) {
        return new SessionState(value, game, gamesPlayed);
    }
}
