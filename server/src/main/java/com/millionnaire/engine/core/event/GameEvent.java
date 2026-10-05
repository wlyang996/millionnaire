package com.millionnaire.engine.core.event;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.TileType;
import com.millionnaire.engine.core.state.ConnState;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowRequest;
import com.millionnaire.engine.core.state.GameResult;
import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/**
 * 对局事件。<b>根接口不覆盖可见性</b>，继承 {@link Event} 的 SERVER_ONLY 默认值：
 * 公开事件逐个实现 {@link PublicEvent}，私有事件声明 PRIVATE 与接收者（手牌、查询结果等，M4）。
 * 携带随机结果的事件（OrderNumberDrawn、DiceRolled、JailRolled）在演化时消费对应的 RandomDrawn 并核对。
 */
public sealed interface GameEvent extends Event {

    // ------------------------------------------------------------ 开局与结束

    /** 开局：固定参与者（按入房先后，随后由 TurnOrderFixed 定序）、冻结设置、开局时间；按设置开立账本。 */
    record GameStarted(long gameNo, List<String> seats, RoomSettings settings, long startedAt)
            implements GameEvent, PublicEvent {
        public GameStarted {
            seats = Immutable.list(seats);
        }
    }

    /** R1 开局抽数（首抽或同分子组重抽），1..orderNumberMax，公开。 */
    record OrderNumberDrawn(String playerId, int value) implements GameEvent, PublicEvent {
    }

    /** 行动顺序确定（整局固定），必须与抽数序列推导出的顺序一致。 */
    record TurnOrderFixed(List<String> order) implements GameEvent, PublicEvent {
        public TurnOrderFixed {
            order = Immutable.list(order);
        }
    }

    /** 全局时钟开始（永不暂停）：到时时刻与 GLOBAL_END 任务。 */
    record GameClockStarted(long endsAt, long taskId) implements GameEvent, PublicEvent {
    }

    /** 全局到时：进入 DRAINING，不再启动新回合。 */
    record DrainingStarted(long at) implements GameEvent, PublicEvent {
    }

    /** 对局结束并回到大厅；result 为结算摘要（管理端中止为 null）；全员取消准备。 */
    record GameEnded(long gameNo, String reason, GameResult result) implements GameEvent, PublicEvent {
    }

    // ------------------------------------------------------------ 回合

    record TurnStarted(long turnNo, String playerId) implements GameEvent, PublicEvent {
    }

    /** 回合进入某阶段：绑定该阶段的 TURN 窗口（AWAITING_FLOW 为 0），并记录结束后的继续位置。 */
    record TurnStageEntered(TurnStage stage, long windowId, String continuation) implements GameEvent, PublicEvent {
    }

    /** 开局发牌（R2，加权抽取）：牌面只发给本人。 */
    record CardDealt(String recipient, CardType card) implements GameEvent {
        @Override
        public Visibility visibility() {
            return Visibility.PRIVATE;
        }
    }

    /** 公开的发牌结果：只有张数。 */
    record CardsDealt(String playerId, int handCount) implements GameEvent, PublicEvent {
    }

    record TurnEnded(long turnNo, String playerId) implements GameEvent, PublicEvent {
    }

    /** 移动骰（R3），公开。 */
    record DiceRolled(String playerId, int value, boolean auto) implements GameEvent, PublicEvent {
    }

    /** 固定方向前进（M1 只有前进）。 */
    record PlayerMoved(String playerId, int from, int to, int steps) implements GameEvent, PublicEvent {
    }

    /** 起点奖励（系统 → 玩家，经账本记账）；每回合最多一次。 */
    record StartRewardPaid(String playerId, long amount, long turnNo) implements GameEvent, PublicEvent {
    }

    /** 落点；placeholder 为 true 表示该格效果尚未接入（M2 起）。 */
    record Landed(String playerId, int tile, TileType type, boolean placeholder) implements GameEvent, PublicEvent {
    }

    // ------------------------------------------------------------ 监狱

    record PlayerJailed(String playerId) implements GameEvent, PublicEvent {
    }

    /** 出狱判定骰（R4），公开；点数不作移动点数。 */
    record JailRolled(String playerId, int value, boolean auto) implements GameEvent, PublicEvent {
    }

    record JailFailed(String playerId, int failures) implements GameEvent, PublicEvent {
    }

    record BailPaid(String playerId, long amount) implements GameEvent, PublicEvent {
    }

    record JailReleased(String playerId, ReleaseReason reason) implements GameEvent, PublicEvent {
    }

    /** 出狱原因。 */
    enum ReleaseReason {
        EVEN_ROLL, THIRD_FAILURE, BAIL
    }

    // ------------------------------------------------------------ 控制与自动动作

    record ControlChanged(String playerId, ControlMode mode) implements GameEvent, PublicEvent {
    }

    /** 连接状态变化；observation 为外层连接判定的观测序号（单调）。 */
    record ConnectionChanged(String playerId, ConnState conn, long observation) implements GameEvent, PublicEvent {
    }

    /** 为当前回合窗口安排自动动作任务（服务端细节，不公开）。 */
    record AutoActArmed(long windowId, long taskId) implements GameEvent {
    }

    /** 自动动作任务失效（已执行、被取消或已过期）。 */
    record AutoActDisarmed(long taskId) implements GameEvent {
    }

    // ------------------------------------------------------------ 流程

    /** 流程协调事件（{@code FlowCoordinator} 产生与演化）。 */
    sealed interface FlowEvent extends GameEvent {
    }

    record WindowOpened(FlowFrame frame) implements FlowEvent, PublicEvent {
    }

    record WindowPaused(long windowId, long at) implements FlowEvent, PublicEvent {
    }

    record WindowResumed(long windowId, long at, long deadlineTaskId) implements FlowEvent, PublicEvent {
    }

    record WindowClosed(long windowId, CloseReason reason) implements FlowEvent, PublicEvent {
    }

    record FlowRequested(FlowRequest request) implements FlowEvent, PublicEvent {
    }

    /** 在安全点从队首取出申请（随后由所属模块开启对应流程）；每个安全点最多一次。 */
    record FlowDequeued(long requestId) implements FlowEvent, PublicEvent {
    }

    /** 进入第 safePointNo 个安全点。 */
    record SafePointEntered(long safePointNo) implements FlowEvent, PublicEvent {
    }

    /** 排队申请被取消并退还（全局到时等）。 */
    record FlowRequestCancelled(long requestId) implements FlowEvent, PublicEvent {
    }

    /** 窗口关闭原因。 */
    enum CloseReason {
        /** 玩家已操作。 */
        ACTED,
        /** 截止时间到，所属模块已执行超时自动动作。 */
        EXPIRED,
        /** 恢复时剩余时间为 0，所属模块已立即执行自动动作。 */
        EXHAUSTED,
        /** 流程被取消（如对局结束、参与者离开）。 */
        CANCELLED
    }
}
