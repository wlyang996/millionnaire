package com.millionnaire.engine.core.state;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/**
 * 按观察者投影的对局视图：所有人可见的公开数据（行动顺序、位置、现金与冻结、手牌张数、监狱、控制与连接状态、
 * 当前回合与窗口的开启/截止时刻、地产（在 board 中）、当前落点步骤、进行中的债务，O20），以及观察者本人的私有字段
 * （myHand；旁观者或他人为空）。随机状态、任务 ID 等内核细节始终不在视图中。
 * <p>仅凭最新投影即可恢复经济窗口（M2b E6）：落点处于哪一步、决策是否仍待做；债务的金额、债权人、段数、
 * 第二段是否已点"继续"（继续按钮是否仍有效）。
 */
public record GameView(long gameNo, GamePhase phase, List<PublicPlayer> players, List<OrderDraw> orderDraws,
                       BoardState board, long turnNo, String currentPlayer, TurnStage stage, long globalEndsAt,
                       List<OpenWindow> windows, PublicLanding landing, PublicDebt debt, List<CardType> myHand,
                       PublicMinigame minigame, PublicCards cards, PublicAuction auction, PublicTrade trade) {
    public GameView(long gameNo, GamePhase phase, List<PublicPlayer> players, List<OrderDraw> orderDraws,
                    BoardState board, long turnNo, String currentPlayer, TurnStage stage, long globalEndsAt,
                    List<OpenWindow> windows, PublicLanding landing, PublicDebt debt, List<CardType> myHand,
                    PublicMinigame minigame, PublicCards cards, PublicAuction auction) {
        this(gameNo, phase, players, orderDraws, board, turnNo, currentPlayer, stage, globalEndsAt, windows, landing, debt, myHand,
                minigame, cards, auction, null);
    }

    public GameView(long gameNo, GamePhase phase, List<PublicPlayer> players, List<OrderDraw> orderDraws,
                    BoardState board, long turnNo, String currentPlayer, TurnStage stage, long globalEndsAt,
                    List<OpenWindow> windows, PublicLanding landing, PublicDebt debt, List<CardType> myHand,
                    PublicMinigame minigame, PublicCards cards) {
        this(gameNo, phase, players, orderDraws, board, turnNo, currentPlayer, stage, globalEndsAt, windows, landing, debt, myHand,
                minigame, cards, null, null);
    }

    public GameView(long gameNo, GamePhase phase, List<PublicPlayer> players, List<OrderDraw> orderDraws,
                    BoardState board, long turnNo, String currentPlayer, TurnStage stage, long globalEndsAt,
                    List<OpenWindow> windows, PublicLanding landing, PublicDebt debt, List<CardType> myHand,
                    PublicMinigame minigame) {
        this(gameNo, phase, players, orderDraws, board, turnNo, currentPlayer, stage, globalEndsAt, windows, landing, debt, myHand,
                minigame, null, null, null);
    }

    public GameView(long gameNo, GamePhase phase, List<PublicPlayer> players, List<OrderDraw> orderDraws,
                    BoardState board, long turnNo, String currentPlayer, TurnStage stage, long globalEndsAt,
                    List<OpenWindow> windows, PublicLanding landing, PublicDebt debt, List<CardType> myHand) {
        this(gameNo, phase, players, orderDraws, board, turnNo, currentPlayer, stage, globalEndsAt, windows, landing, debt, myHand, null);
    }

    public GameView {
        players = Immutable.list(players);
        orderDraws = Immutable.list(orderDraws);
        windows = Immutable.list(windows);
        myHand = Immutable.list(myHand);
    }

    /** 公开的玩家数据（顺序即行动顺序）；frozen 为冻结余额（M5 起由拍卖报价产生，M2 恒为 0）。 */
    public record PublicPlayer(String playerId, int position, long cash, long frozen, int handCount, LifeState life,
                               boolean inJail, int jailFailures, ControlMode control, ConnState conn) {
    }

    /** 公开的窗口信息（不含任务 ID 等内核细节）。 */
    public record OpenWindow(long windowId, FlowKind kind, String owner, long opensAt, long deadline, boolean paused) {
    }

    /** 当前落点：编号、格子、当前等待的步骤、决策是否仍待做（窗口内可操作）。 */
    public record PublicLanding(long landingId, int tile, LandingStep step, boolean decisionPending) {
    }

    /**
     * 进行中的债务：债务人、债权人（null = 系统）、欠款、段数（1 / 2）、第二段是否已点"继续"、
     * 继续按钮是否可用（第二段且尚未点）、对应窗口。
     */
    public record PublicDebt(long debtId, String debtor, String creditor, long amount, int segment, boolean continued,
                             boolean continueAvailable, long windowId) {
    }

    /** 进行中的交易：卖家、买家、地块、价格、交易窗口（所有者为卖家，买家答复）。 */
    public record PublicTrade(String seller, String buyer, int tile, long price, long windowId) {
    }

    /**
     * 进行中的拍卖：类别（LAND 土地拍卖 / CARD 拍卖卡）、地块、卖家或发起人、基数、起拍价、最小加价、封顶（一口价）、
     * 当前最高价与出价者、下一次报价的下限、总时长上限、拍卖窗口。
     */
    public record PublicAuction(String kind, int tile, String seller, String initiator, long basis, long start, long minRaise,
                                long cap, long highBid, String highBidder, long minimumBid, long hardEnd, long windowId) {
    }

    /** 道具的公开部分：本轮已用掉主动用卡机会的玩家；等待中的攻击响应（谁对谁的哪块地用了什么、对方可用的响应卡及其窗口）。 */
    public record PublicCards(List<String> chanceUsed, PublicResponse response) {
        public PublicCards {
            chanceUsed = Immutable.list(chanceUsed);
        }
    }

    public record PublicResponse(String attacker, String owner, int tile, CardType attack, CardType response, long windowId) {
    }

    /**
     * 进行中的虎口拔牙（危险牙保密，不在视图中）：小游戏编号（= 落点编号）、触发者、参与者（选牙顺序）、牙齿数、
     * 已按下的牙（按先后）、当前选牙者及其窗口。
     */
    public record PublicMinigame(long minigameId, String trigger, List<String> participants, int teeth, List<Integer> picks,
                                 String picker, long windowId) {
        public PublicMinigame {
            participants = Immutable.list(participants);
            picks = Immutable.list(picks);
        }
    }
}
