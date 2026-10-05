package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.event.GameEvent.CloseReason;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.FlowRequest;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.time.ScheduledTask;

/**
 * 覆盖流程模块（M1 占位）：负责从安全点启动排队流程，以及覆盖窗口到期时按所属模块分派关闭动作。
 * M1 尚无拍卖、交易等真实规则，超时默认动作为"无结果关闭"（拍卖流拍、交易视为拒绝）；
 * M2 起各覆盖流程模块在此按 {@link FlowKind} 分派自己的超时、关闭与耗尽动作。
 */
final class OverlayModule {
    private OverlayModule() {
    }

    /** 在安全点启动一个排队申请（覆盖窗口位于栈底，回合处于 AWAITING_FLOW）。 */
    static void start(DecisionContext<SessionState> ctx, FlowRequest request) {
        GameModule.openOverlay(ctx, request.kind(), request.applicant(), 0, durationMs(ctx.config(), request.kind()), "RETURN");
    }

    /** 覆盖窗口到期：确认是当前窗口的截止任务后，按所属模块关闭（M1 占位：无结果关闭）。 */
    static void onTask(DecisionContext<SessionState> ctx, ScheduledTask task) {
        if (!ctx.state().inGame()) {
            return;
        }
        FlowCoordinator.expiredFrame(ctx.state().game().flow(), task)
                .filter(f -> f.kind() != FlowKind.TURN)
                .ifPresent(f -> GameModule.closeOverlay(ctx, f.windowId(), CloseReason.EXPIRED));
    }

    static long durationMs(RuleConfig config, FlowKind kind) {
        return switch (kind) {
            case AUCTION -> config.timing().auctionDurationMs();
            case TRADE -> config.timing().tradeResponseMs();
            case DEBT -> config.timing().debtSegmentMs();
            case MINIGAME -> config.timing().toothPickMs();
            case DISCARD -> config.timing().discardWindowMs();
            case ATTACK, RESPONSE -> config.timing().responseWindowMs();
            case TURN -> throw new IllegalArgumentException("TURN windows are opened by the turn module");
        };
    }
}
