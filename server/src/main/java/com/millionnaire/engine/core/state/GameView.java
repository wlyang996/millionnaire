package com.millionnaire.engine.core.state;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/**
 * 按观察者投影的对局视图：所有人可见的公开数据（行动顺序、位置、现金、手牌张数、监狱、控制与连接状态、
 * 当前回合与窗口的开启/截止时刻，O20），以及观察者本人的私有字段（myHand；旁观者或他人为空）。
 * 随机状态、任务 ID 等内核细节始终不在视图中。
 */
public record GameView(long gameNo, GamePhase phase, List<PublicPlayer> players, List<OrderDraw> orderDraws,
                       BoardState board, long turnNo, String currentPlayer, TurnStage stage, long globalEndsAt,
                       List<OpenWindow> windows, List<CardType> myHand) {
    public GameView {
        players = Immutable.list(players);
        orderDraws = Immutable.list(orderDraws);
        windows = Immutable.list(windows);
        myHand = Immutable.list(myHand);
    }

    /** 公开的玩家数据（顺序即行动顺序）。 */
    public record PublicPlayer(String playerId, int position, long cash, int handCount, LifeState life, boolean inJail,
                               int jailFailures, ControlMode control, ConnState conn) {
    }

    /** 公开的窗口信息（不含任务 ID 等内核细节）。 */
    public record OpenWindow(long windowId, FlowKind kind, String owner, long opensAt, long deadline, boolean paused) {
    }
}
