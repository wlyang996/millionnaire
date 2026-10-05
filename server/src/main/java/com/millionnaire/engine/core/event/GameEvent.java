package com.millionnaire.engine.core.event;

import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/**
 * 对局边界事件（公开）。M1 在此加入回合、移动等对局事件；涉及手牌等秘密的事件须单独声明 PRIVATE / SERVER_ONLY。
 */
public sealed interface GameEvent extends Event {

    @Override
    default Visibility visibility() {
        return Visibility.PUBLIC;
    }

    /** 开局：固定参与者（按入房先后，M1 改为抽数定序）与设置快照。 */
    record GameStarted(long gameNo, List<String> seats, RoomSettings settings, long startedAt) implements GameEvent {
        public GameStarted {
            seats = Immutable.list(seats);
        }
    }

    /** 对局结束并回到大厅；全员取消准备（requirements 第 2 节"结算后回原房间……全员重新准备"）。 */
    record GameEnded(long gameNo, String reason) implements GameEvent {
    }
}
