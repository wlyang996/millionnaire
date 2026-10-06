package com.millionnaire.engine.core.state;

import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
import java.util.Optional;

/**
 * 对局状态，按归属拆为子状态：参与者名册（顺序即行动顺序，含私有手牌；与大厅成员分开）、开局抽数、棋盘（含地产）、
 * 回合（含落点）、流程、账本（现金唯一来源）、全局时钟、进行中的债务（至多一个）、延后认输（按收到顺序）。
 * <b>含私有数据，不得直接下发</b>，客户端只能拿到 {@link GameView}。
 */
public record GameState(long gameNo, long startedAt, RoomSettings settings, GamePhase phase, List<PlayerState> players,
                        List<OrderDraw> orderDraws, BoardState board, TurnState turn, FlowState flow, Ledger ledger,
                        GameClock clock, DebtState debt, List<String> pendingSurrenders) {
    public GameState {
        players = Immutable.list(players);
        orderDraws = Immutable.list(orderDraws);
        pendingSurrenders = Immutable.list(pendingSurrenders);
    }

    public Optional<PlayerState> player(String playerId) {
        return players.stream().filter(p -> p.playerId().equals(playerId)).findFirst();
    }

    public List<PlayerState> alive() {
        return players.stream().filter(PlayerState::alive).toList();
    }

    public GameState withFlow(FlowState value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, value, ledger, clock, debt,
                pendingSurrenders);
    }

    public GameState withPlayers(List<PlayerState> value) {
        return new GameState(gameNo, startedAt, settings, phase, value, orderDraws, board, turn, flow, ledger, clock, debt,
                pendingSurrenders);
    }

    public GameState withPlayer(PlayerState value) {
        return withPlayers(players.stream().map(p -> p.playerId().equals(value.playerId()) ? value : p).toList());
    }

    public GameState withOrderDraws(List<OrderDraw> value) {
        return new GameState(gameNo, startedAt, settings, phase, players, value, board, turn, flow, ledger, clock, debt,
                pendingSurrenders);
    }

    public GameState withBoard(BoardState value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, value, turn, flow, ledger, clock, debt,
                pendingSurrenders);
    }

    public GameState withTurn(TurnState value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, value, flow, ledger, clock, debt,
                pendingSurrenders);
    }

    public GameState withLedger(Ledger value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, value, clock, debt,
                pendingSurrenders);
    }

    public GameState withClock(GameClock value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, value, debt,
                pendingSurrenders);
    }

    public GameState withPhase(GamePhase value) {
        return new GameState(gameNo, startedAt, settings, value, players, orderDraws, board, turn, flow, ledger, clock, debt,
                pendingSurrenders);
    }

    public GameState withDebt(DebtState value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, clock, value,
                pendingSurrenders);
    }

    public GameState withPendingSurrenders(List<String> value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, clock, debt,
                value);
    }
}
