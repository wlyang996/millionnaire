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
                       List<OpenWindow> windows, PublicLanding landing, PublicDebt debt, List<CardType> myHand) {
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
}
