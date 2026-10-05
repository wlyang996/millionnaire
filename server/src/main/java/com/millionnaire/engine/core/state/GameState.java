package com.millionnaire.engine.core.state;

import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
import java.util.Optional;

/**
 * 对局状态，按归属拆为子状态：玩家（顺序即行动顺序，含私有手牌）、开局抽数、棋盘、回合、流程、账本（现金唯一来源）、全局时钟。
 * <b>含私有数据，不得直接下发</b>，客户端只能拿到 {@link GameView}。
 */
public record GameState(long gameNo, long startedAt, RoomSettings settings, GamePhase phase, List<PlayerState> players,
                        List<OrderDraw> orderDraws, BoardState board, TurnState turn, FlowState flow, Ledger ledger,
                        GameClock clock) {
    public GameState {
        players = Immutable.list(players);
        orderDraws = Immutable.list(orderDraws);
    }

    public Optional<PlayerState> player(String playerId) {
        return players.stream().filter(p -> p.playerId().equals(playerId)).findFirst();
    }

    public GameState withFlow(FlowState value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, value, ledger, clock);
    }

    public GameState withPlayers(java.util.List<PlayerState> value) {
        return new GameState(gameNo, startedAt, settings, phase, value, orderDraws, board, turn, flow, ledger, clock);
    }

    public GameState withPlayer(PlayerState value) {
        return withPlayers(players.stream().map(p -> p.playerId().equals(value.playerId()) ? value : p).toList());
    }

    public GameState withOrderDraws(List<OrderDraw> value) {
        return new GameState(gameNo, startedAt, settings, phase, players, value, board, turn, flow, ledger, clock);
    }

    public GameState withTurn(TurnState value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, value, flow, ledger, clock);
    }

    public GameState withLedger(Ledger value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, value, clock);
    }

    public GameState withClock(GameClock value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, value);
    }

    public GameState withPhase(GamePhase value) {
        return new GameState(gameNo, startedAt, settings, value, players, orderDraws, board, turn, flow, ledger, clock);
    }
}
