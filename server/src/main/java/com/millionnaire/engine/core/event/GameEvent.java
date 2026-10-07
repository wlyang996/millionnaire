package com.millionnaire.engine.core.event;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.TileType;
import com.millionnaire.engine.core.state.MoveKind;
import com.millionnaire.engine.core.state.ConnState;
import com.millionnaire.engine.core.state.Continuation;
import com.millionnaire.engine.core.state.DebtState;
import com.millionnaire.engine.core.state.Elimination;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.core.state.LifeState;
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
    record EventDrawn(String playerId, long landingId, int cursor, com.millionnaire.engine.config.EventKind kind,
                      long amount, MoveKind moveKind, int distance, boolean auto) implements GameEvent, PublicEvent { }
    record EventRewardPaid(String playerId, long landingId, int cursor, long amount) implements GameEvent, PublicEvent { }
    record FeeCharged(String payer, com.millionnaire.engine.core.state.FeeSource source) implements GameEvent, PublicEvent { }
    record FeePaid(String payer, com.millionnaire.engine.core.state.FeeSource source) implements GameEvent, PublicEvent { }
    record EventMoveCommitted(String playerId, long landingId, int cursor, MoveKind kind, int distance) implements GameEvent, PublicEvent { }
    record EventCardReceived(String recipient, long landingId, int cursor, CardType card) implements GameEvent {
        @Override public Visibility visibility() { return Visibility.PRIVATE; }
    }
    record EventCardDiscarded(String recipient, long landingId, int cursor, int index, CardType card, boolean auto) implements GameEvent {
        @Override public Visibility visibility() { return Visibility.PRIVATE; }
    }
    /** Publicly disclose only that a card was obtained/discarded and the actual hand size. */
    record EventHandCount(String playerId, long landingId, int cursor, int handCount, boolean discarded) implements GameEvent, PublicEvent { }

    // ------------------------------------------------------------ 开局与结束

    /** 开局：固定参与者（按入房先后，随后由 TurnOrderFixed 定序）、冻结设置、开局时间；按设置开立账本。 */
    record GameStarted(long gameNo, List<String> seats, RoomSettings settings, long startedAt)
            implements GameEvent, PublicEvent {
        public GameStarted {
            seats = Immutable.list(seats);
        }
    }

    /** 开局抽数的一轮：本轮依次应抽数的玩家（首抽为全体座位顺序；重抽为各同分组按名次、组内按座位展开）。 */
    record OrderRoundStarted(List<String> drawers) implements GameEvent, PublicEvent {
        public OrderRoundStarted {
            drawers = Immutable.list(drawers);
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

    /** 正常对局结束并回到大厅；result 必须为非空结算摘要；全员取消准备。 */
    record GameEnded(long gameNo, String reason, GameResult result) implements GameEvent, PublicEvent {
    }

    /** 管理中止：inputSeq 关联已接纳的系统 EndGame；只有名称或空结算摘要不构成授权。 */
    record GameAborted(long gameNo, String reason, long inputSeq) implements GameEvent, PublicEvent {
    }

    // ------------------------------------------------------------ 回合

    record TurnStarted(long turnNo, String playerId) implements GameEvent, PublicEvent {
    }

    /**
     * 回合进入某阶段：绑定该阶段的 TURN 窗口（AWAITING_FLOW 为 0），记录结束后的继续位置，
     * 以及 AWAITING_FLOW 时继续位置的最早开放时刻 notBefore（其他阶段为 0）。
     */
    record TurnStageEntered(TurnStage stage, long windowId, Continuation continuation, long notBefore)
            implements GameEvent, PublicEvent {
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

    /** 移动段：链编号、段序号与来源种类随日志重建；M3a 仅接纳已核对骰子的段。 */
    record PlayerMoved(String playerId, int from, int to, int steps, long chainId, int segmentNo, MoveKind kind,
                       int plannedDistance, com.millionnaire.engine.core.state.Roadblock stoppedBy)
            implements GameEvent, PublicEvent {
        public PlayerMoved(String playerId, int from, int to, int steps, long chainId, int segmentNo, MoveKind kind) {
            this(playerId, from, to, steps, chainId, segmentNo, kind, steps, null);
        }
        public PlayerMoved(String playerId, int from, int to, int steps) {
            this(playerId, from, to, steps, 0, 0, MoveKind.DICE);
        }
    }

    /** 骰子行动开始一条移动链；重定向沿用链编号，不能伪造新行动。 */
    record MoveChainStarted(long chainId, long turnNo, String playerId, int origin) implements GameEvent, PublicEvent { }

    /** Internal mechanism source, not evidence of card ownership or a player-accessible command. */
    record MovementEffectCommitted(com.millionnaire.engine.core.state.MovementEffect source) implements GameEvent { }
    record RoadblockPlaced(String playerId, long turnNo, long windowId, com.millionnaire.engine.core.state.Roadblock roadblock)
            implements GameEvent, PublicEvent { }
    /** Must immediately follow the verified stopped movement; removes exactly that board object. */
    record RoadblockTriggered(String playerId, long turnNo, long chainId, int segmentNo,
                             com.millionnaire.engine.core.state.Roadblock roadblock) implements GameEvent, PublicEvent { }

    /** 起点奖励（系统 → 玩家，经账本记账）；每回合最多一次。 */
    record StartRewardPaid(String playerId, long amount, long turnNo) implements GameEvent, PublicEvent {
    }

    /** 落点；placeholder 为 true 表示该格效果尚未接入（事件格 M3、游戏区 M6）。 */
    record Landed(String playerId, int tile, TileType type, boolean placeholder) implements GameEvent, PublicEvent {
    }

    // ------------------------------------------------------------ 落点推进器与经济（M2）

    /** 落点推进器开始：分配落点编号（整局单调递增）。 */
    record LandingStarted(long landingId, String playerId, int tile, long chainId) implements GameEvent, PublicEvent {
        public LandingStarted(long landingId, String playerId, int tile) { this(landingId, playerId, tile, 0); }
    }

    /** 落点进入一个需要等待的子阶段；payment 为 DEBT 时的欠款，其余为 0。 */
    record LandingStepEntered(long landingId, LandingStep step, long payment, int cursor) implements GameEvent, PublicEvent {
        public LandingStepEntered(long landingId, LandingStep step, long payment) { this(landingId, step, payment, -1); }
    }

    /** 落点结算完成：必须没有待处理的必要步骤、决策已消费、费用已结清（E1）。 */
    record LandingFinished(long landingId) implements GameEvent, PublicEvent {
    }

    /** 落点因当前玩家被淘汰而中止（不要求必要步骤完成）。 */
    record LandingAborted(long landingId) implements GameEvent, PublicEvent {
    }

    record PropertyBought(String playerId, int tile, long price) implements GameEvent, PublicEvent {
    }

    record PurchaseDeclined(String playerId, int tile, boolean auto) implements GameEvent, PublicEvent {
    }

    record PropertyUpgraded(String playerId, int tile, int level, long cost) implements GameEvent, PublicEvent {
    }

    record UpgradeSkipped(String playerId, int tile, boolean auto) implements GameEvent, PublicEvent {
    }

    /** 费用成立（M4 免租响应在此之前；M2 无卡效果）。 */
    record RentCharged(String payer, String owner, int tile, long amount) implements GameEvent, PublicEvent {
    }

    record RentPaid(String payer, String owner, int tile, long amount) implements GameEvent, PublicEvent {
    }

    /** 抵押：银行 100%（emergency = false）或应急比例（emergency = true）；principal 为实得金额（赎回本金）。 */
    record AssetMortgaged(String playerId, int tile, long principal, boolean emergency) implements GameEvent, PublicEvent {
    }

    /** 赎回：归还本金，另付手续费（银行内为 0）。 */
    record AssetRedeemed(String playerId, int tile, long principal, long fee) implements GameEvent, PublicEvent {
    }

    record BankFinished(String playerId, boolean auto) implements GameEvent, PublicEvent {
    }

    /** 债务成立（处理路径锁定为手动抵押，第一段窗口随后开启）。 */
    record DebtCreated(DebtState debt) implements GameEvent, PublicEvent {
    }

    /** 债务窗口进入第 segment 段（第二段即"继续抵押 / 确认破产"弹窗，默认继续）。 */
    record DebtSegmentStarted(long debtId, int segment, long windowId) implements GameEvent, PublicEvent {
    }

    /** 第二段弹窗选择"继续"（不延长计时）。 */
    record DebtContinued(long debtId) implements GameEvent, PublicEvent {
    }

    /** 筹足即付：一次付清全部欠款。 */
    record DebtPaid(long debtId, long amount) implements GameEvent, PublicEvent {
    }

    /** 开始破产清算（debtId 为 0 表示无欠款的认输清算）。 */
    record LiquidationStarted(String playerId, long debtId, LifeState outcome) implements GameEvent, PublicEvent {
    }

    /** 未抵押资产按应急比例变现（O5+O6 产品决定）。 */
    record AssetLiquidated(String playerId, int tile, long proceeds) implements GameEvent, PublicEvent {
    }

    /** 资产由系统回收（已抵押资产、或认输时的全部资产）：产权、等级、抵押清空，变为无主。 */
    record AssetReclaimed(String playerId, int tile) implements GameEvent, PublicEvent {
    }

    /** 偿债：有多少给多少，不超过欠款。 */
    record DebtSettled(long debtId, String creditor, long paid) implements GameEvent, PublicEvent {
    }

    /** 剩余现金由系统回收（金额可为 0）；清算结束。 */
    record CashReclaimed(String playerId, long amount) implements GameEvent, PublicEvent {
    }

    /** 淘汰（破产或认输）：记录淘汰序号、批次与批次前净资产。 */
    record PlayerEliminated(String playerId, LifeState life, Elimination elimination) implements GameEvent, PublicEvent {
    }

    /** 认输延后到当前流程结束后处理（O7）。 */
    record SurrenderDeferred(String playerId) implements GameEvent, PublicEvent {
    }

    /** 开始处理一批延后认输（批次编号）。 */
    record SurrenderBatchStarted(long batch) implements GameEvent, PublicEvent {
    }

    record SurrenderBatchEnded(long batch) implements GameEvent, PublicEvent {
    }

    // ------------------------------------------------------------ 道具（requirements 第 14 节）

    /**
     * 用出一张卡（从手牌移除同种的第一张）：active 为主动卡（消耗本轮主动用卡机会），否则为响应卡；
     * tile 为目标格（-1 = 无），target 为目标玩家（地产所有者 / 查询对象 / 被响应的攻击者，可为 null）。
     */
    record CardUsed(String playerId, CardType card, int tile, String target, boolean active) implements GameEvent, PublicEvent {
    }

    /** 攻击卡的目标所有者持有对应响应卡（房屋保护 / 拒绝购买）：为其开 10 秒响应窗（O4：只在持卡时弹出）。 */
    record ResponseOffered(String attacker, String owner, int tile, CardType attack, CardType response)
            implements GameEvent, PublicEvent {
    }

    /** 所有者不使用响应卡（主动放弃或超时）：攻击照常生效。 */
    record ResponseDeclined(String owner, int tile, boolean auto) implements GameEvent, PublicEvent {
    }

    /** 攻击被响应卡抵挡：双方的卡都已消耗，地产不变（auto 为托管 / 掉线 / 暂离自动使用）。 */
    record AttackBlocked(String attacker, String owner, int tile, CardType attack, CardType response, boolean auto)
            implements GameEvent, PublicEvent {
    }

    /** 建造卡：自己的普通地产免费升一级。 */
    record PropertyBuilt(String playerId, int tile, int level) implements GameEvent, PublicEvent {
    }

    /** 降级卡：他人未抵押普通地产降一级。 */
    record PropertyDowngraded(String attacker, String owner, int tile, int level) implements GameEvent, PublicEvent {
    }

    /** 拆楼卡：清除全部升级，保留所有权。 */
    record PropertyDemolished(String attacker, String owner, int tile) implements GameEvent, PublicEvent {
    }

    /** 清地卡：清除等级与所有权，变为无主（不补偿，#10）。 */
    record PropertyCleared(String attacker, String owner, int tile) implements GameEvent, PublicEvent {
    }

    /** 强制购房：以标准价值 1.5 倍（向下取整）买下，款归原主；等级保留。 */
    record PropertyForceBought(String buyer, String owner, int tile, long price) implements GameEvent, PublicEvent {
    }

    /** 查询结果：目标玩家此刻的手牌快照，只发给使用者（#10）。 */
    record QueryRevealed(String recipient, String target, List<CardType> cards) implements GameEvent {
        public QueryRevealed {
            cards = Immutable.list(cards);
        }

        @Override
        public Visibility visibility() {
            return Visibility.PRIVATE;
        }
    }

    /** 免租卡免除本次租金（响应先于费用成立，O4）。 */
    record RentWaived(String payer, String owner, int tile, boolean auto) implements GameEvent, PublicEvent {
    }

    /** 持有免租卡但不使用（主动放弃或超时）：随后照常缴租。 */
    record RentWaiverDeclined(String payer, int tile, boolean auto) implements GameEvent, PublicEvent {
    }

    // ------------------------------------------------------------ 小游戏（虎口拔牙）

    /**
     * 游戏区启动虎口拔牙：落点编号即小游戏编号；参与者为全部存活玩家（从触发者起按行动顺序）；牙齿数 = 人数 × 2。
     * 危险牙在演化时由 R10 抽取结果确定，<b>不出现在本事件中</b>。
     */
    record MinigameStarted(long landingId, int cursor, String trigger, List<String> participants, int teeth)
            implements GameEvent, PublicEvent {
        public MinigameStarted {
            participants = Immutable.list(participants);
        }
    }

    /** 选牙：auto 为超时 / 托管 / 掉线时服务端代选（R11 在剩余牙中等概率抽取）。 */
    record ToothPicked(long landingId, String playerId, int tooth, boolean auto) implements GameEvent, PublicEvent {
    }

    /** 小游戏结束：按到危险牙者为输家，危险牙公开；其余参与者各获系统奖励 reward（输家不扣钱）。 */
    record MinigameEnded(long landingId, String loser, int danger, long reward) implements GameEvent, PublicEvent {
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
        EVEN_ROLL, THIRD_FAILURE, BAIL,
        /** 出狱卡（占用本回合主动用卡机会，O9）。 */
        CARD
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
