package com.millionnaire.engine.core.state;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/**
 * 对局参与者（名册在开局时固定，与大厅成员分开：被淘汰者离房后仍留在名册中参与结算）。
 * 现金不在此处，唯一来源是 {@link GameState#ledger()}。hand 为<b>私有</b>字段，只能经 GameView 投影给本人。
 * jailFailures 为本次关押已失败的判定次数（0..2）；connObservation 为最后采纳的连接观测序号（过期观测被拒）；
 * elimination 为淘汰记录（存活时为 null）。
 */
public record PlayerState(String playerId, int position, List<CardType> hand, LifeState life, boolean inJail,
                          int jailFailures, ControlMode control, ConnState conn, long connObservation,
                          Elimination elimination) {
    public PlayerState {
        hand = Immutable.list(hand);
    }

    public static PlayerState seated(String playerId) {
        return new PlayerState(playerId, 0, List.of(), LifeState.ALIVE, false, 0, ControlMode.MANUAL, ConnState.ONLINE, 0,
                null);
    }

    public boolean alive() {
        return life == LifeState.ALIVE;
    }

    /** 是否由服务端代为操作：非手动模式，或已确认掉线（疑似断线不自动，已裁决 2）。 */
    public boolean automated() {
        return control != ControlMode.MANUAL || conn == ConnState.OFFLINE;
    }

    public PlayerState at(int value) {
        return new PlayerState(playerId, value, hand, life, inJail, jailFailures, control, conn, connObservation, elimination);
    }

    public PlayerState jail(boolean value, int failures) {
        return new PlayerState(playerId, position, hand, life, value, failures, control, conn, connObservation, elimination);
    }

    public PlayerState control(ControlMode value) {
        return new PlayerState(playerId, position, hand, life, inJail, jailFailures, value, conn, connObservation, elimination);
    }

    public PlayerState conn(ConnState value, long observation) {
        return new PlayerState(playerId, position, hand, life, inJail, jailFailures, control, value, observation, elimination);
    }

    public PlayerState withHand(List<CardType> value) {
        return new PlayerState(playerId, position, value, life, inJail, jailFailures, control, conn, connObservation,
                elimination);
    }

    /** 淘汰：清空手牌与监狱标记，记录淘汰信息。 */
    public PlayerState eliminated(LifeState how, Elimination value) {
        return new PlayerState(playerId, position, List.of(), how, false, 0, control, conn, connObservation, value);
    }
}
