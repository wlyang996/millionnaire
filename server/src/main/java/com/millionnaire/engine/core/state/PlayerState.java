package com.millionnaire.engine.core.state;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/**
 * 对局中的玩家。现金不在此处，唯一来源是 {@link GameState#ledger()}。hand 为<b>私有</b>字段，只能经 GameView 投影给本人。
 * jailFailures 为本次关押已失败的判定次数（0..2）；connObservation 为最后采纳的连接观测序号（过期观测被拒）。
 */
public record PlayerState(String playerId, int position, List<CardType> hand, LifeState life, boolean inJail,
                          int jailFailures, ControlMode control, ConnState conn, long connObservation) {
    public PlayerState {
        hand = Immutable.list(hand);
    }

    public static PlayerState seated(String playerId) {
        return new PlayerState(playerId, 0, List.of(), LifeState.ALIVE, false, 0, ControlMode.MANUAL, ConnState.ONLINE, 0);
    }

    /** 是否由服务端代为操作：非手动模式，或已确认掉线（疑似断线不自动，已裁决 2）。 */
    public boolean automated() {
        return control != ControlMode.MANUAL || conn == ConnState.OFFLINE;
    }

    public PlayerState at(int value) {
        return new PlayerState(playerId, value, hand, life, inJail, jailFailures, control, conn, connObservation);
    }

    public PlayerState jail(boolean value, int failures) {
        return new PlayerState(playerId, position, hand, life, value, failures, control, conn, connObservation);
    }

    public PlayerState control(ControlMode value) {
        return new PlayerState(playerId, position, hand, life, inJail, jailFailures, value, conn, connObservation);
    }

    public PlayerState conn(ConnState value, long observation) {
        return new PlayerState(playerId, position, hand, life, inJail, jailFailures, control, value, observation);
    }

    public PlayerState withHand(List<CardType> value) {
        return new PlayerState(playerId, position, value, life, inJail, jailFailures, control, conn, connObservation);
    }
}
