package com.millionnaire.engine.core.state;

import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
import java.util.Optional;

/**
 * 对局状态，按归属拆为子状态：参与者名册（顺序即行动顺序，含私有手牌；与大厅成员分开）、开局抽数、棋盘（含地产）、
 * 回合（含落点）、流程、账本（现金唯一来源）、全局时钟、进行中的债务（至多一个）、延后认输（按收到顺序）、
 * 进行中的小游戏（至多一个，含保密的危险牙）、道具状态（主动用卡机会、待结算的卡）、进行中的拍卖与交易（各至多一个）。
 * <b>含私有数据，不得直接下发</b>，客户端只能拿到 {@link GameView}。
 */
public record GameState(long gameNo, long startedAt, RoomSettings settings, GamePhase phase, List<PlayerState> players,
                        List<OrderDraw> orderDraws, BoardState board, TurnState turn, FlowState flow, Ledger ledger,
                        GameClock clock, DebtState debt, List<String> pendingSurrenders, MinigameState minigame,
                        CardState cards, AuctionState auction, TradeState trade, GameProgressState progress) {
    public GameState(long gameNo, long startedAt, RoomSettings settings, GamePhase phase, List<PlayerState> players,
                     List<OrderDraw> orderDraws, BoardState board, TurnState turn, FlowState flow, Ledger ledger,
                     GameClock clock, DebtState debt, List<String> pendingSurrenders, MinigameState minigame,
                     CardState cards, AuctionState auction, TradeState trade) {
        this(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, clock, debt,
                pendingSurrenders, minigame, cards, auction, trade, GameProgressState.EMPTY);
    }
    public GameState(long gameNo, long startedAt, RoomSettings settings, GamePhase phase, List<PlayerState> players,
                     List<OrderDraw> orderDraws, BoardState board, TurnState turn, FlowState flow, Ledger ledger,
                     GameClock clock, DebtState debt, List<String> pendingSurrenders, MinigameState minigame, CardState cards,
                     AuctionState auction) {
        this(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, clock, debt, pendingSurrenders,
                minigame, cards, auction, null);
    }

    public GameState(long gameNo, long startedAt, RoomSettings settings, GamePhase phase, List<PlayerState> players,
                     List<OrderDraw> orderDraws, BoardState board, TurnState turn, FlowState flow, Ledger ledger,
                     GameClock clock, DebtState debt, List<String> pendingSurrenders, MinigameState minigame, CardState cards) {
        this(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, clock, debt, pendingSurrenders,
                minigame, cards, null);
    }

    public GameState(long gameNo, long startedAt, RoomSettings settings, GamePhase phase, List<PlayerState> players,
                     List<OrderDraw> orderDraws, BoardState board, TurnState turn, FlowState flow, Ledger ledger,
                     GameClock clock, DebtState debt, List<String> pendingSurrenders, MinigameState minigame) {
        this(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, clock, debt, pendingSurrenders,
                minigame, CardState.EMPTY);
    }

    public GameState(long gameNo, long startedAt, RoomSettings settings, GamePhase phase, List<PlayerState> players,
                     List<OrderDraw> orderDraws, BoardState board, TurnState turn, FlowState flow, Ledger ledger,
                     GameClock clock, DebtState debt, List<String> pendingSurrenders) {
        this(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, clock, debt, pendingSurrenders, null);
    }

    public GameState {
        progress = progress == null ? GameProgressState.EMPTY : progress;
        players = Immutable.list(players);
        orderDraws = Immutable.list(orderDraws);
        pendingSurrenders = Immutable.list(pendingSurrenders);
        cards = cards == null ? CardState.EMPTY : cards;
    }

    public Optional<PlayerState> player(String playerId) {
        return players.stream().filter(p -> p.playerId().equals(playerId)).findFirst();
    }

    public List<PlayerState> alive() {
        return players.stream().filter(PlayerState::alive).toList();
    }

    public GameState withFlow(FlowState value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, value, ledger, clock, debt,
                pendingSurrenders, minigame, cards, auction, trade, progress);
    }

    public GameState withPlayers(List<PlayerState> value) {
        return new GameState(gameNo, startedAt, settings, phase, value, orderDraws, board, turn, flow, ledger, clock, debt,
                pendingSurrenders, minigame, cards, auction, trade, progress);
    }

    public GameState withPlayer(PlayerState value) {
        return withPlayers(players.stream().map(p -> p.playerId().equals(value.playerId()) ? value : p).toList());
    }

    public GameState withOrderDraws(List<OrderDraw> value) {
        return new GameState(gameNo, startedAt, settings, phase, players, value, board, turn, flow, ledger, clock, debt,
                pendingSurrenders, minigame, cards, auction, trade, progress);
    }

    public GameState withBoard(BoardState value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, value, turn, flow, ledger, clock, debt,
                pendingSurrenders, minigame, cards, auction, trade, progress);
    }

    public GameState withTurn(TurnState value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, value, flow, ledger, clock, debt,
                pendingSurrenders, minigame, cards, auction, trade, progress);
    }

    public GameState withLedger(Ledger value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, value, clock, debt,
                pendingSurrenders, minigame, cards, auction, trade, progress);
    }

    public GameState withClock(GameClock value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, value, debt,
                pendingSurrenders, minigame, cards, auction, trade, progress);
    }

    public GameState withPhase(GamePhase value) {
        return new GameState(gameNo, startedAt, settings, value, players, orderDraws, board, turn, flow, ledger, clock, debt,
                pendingSurrenders, minigame, cards, auction, trade, progress);
    }

    public GameState withDebt(DebtState value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, clock, value,
                pendingSurrenders, minigame, cards, auction, trade, progress);
    }

    public GameState withPendingSurrenders(List<String> value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, clock, debt,
                value, minigame, cards, auction, trade, progress);
    }

    public GameState withMinigame(MinigameState value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, clock, debt,
                pendingSurrenders, value, cards, auction, trade, progress);
    }

    public GameState withCards(CardState value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, clock, debt,
                pendingSurrenders, minigame, value, auction, trade, progress);
    }

    public GameState withAuction(AuctionState value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, clock, debt,
                pendingSurrenders, minigame, cards, value, trade, progress);
    }

    public GameState withTrade(TradeState value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, clock, debt,
                pendingSurrenders, minigame, cards, auction, value, progress);
    }
    public GameState withProgress(GameProgressState value) {
        return new GameState(gameNo, startedAt, settings, phase, players, orderDraws, board, turn, flow, ledger, clock, debt,
                pendingSurrenders, minigame, cards, auction, trade, value);
    }
}