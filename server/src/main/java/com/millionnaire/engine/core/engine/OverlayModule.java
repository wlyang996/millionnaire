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

    /** 在安全点启动一个排队申请（覆盖窗口位于栈底，回合处于 AWAITING_FLOW）；leadMs 为尚未播完的动画缓冲（C6）。 */
    static void start(DecisionContext<SessionState> ctx, FlowRequest request, long leadMs) {
        GameModule.openQueuedOverlay(ctx, request, leadMs, durationMs(ctx.config(), request.kind()), "RETURN");
    }

    /** 覆盖窗口到期：确认是当前窗口的截止任务后，按所属模块关闭（M1 占位：无结果关闭）。 */
    static void onTask(DecisionContext<SessionState> ctx, ScheduledTask task) {
        if (!ctx.state().inGame()) {
            return;
        }
        FlowCoordinator.expiredFrame(ctx.state().game().flow(), task)
                .filter(f -> f.kind() != FlowKind.TURN)
                .ifPresent(f -> {
                    if (f.kind() == FlowKind.DEBT) {
                        // 债务两段（M2 P5）：第一段到期 → 第二段弹窗；第二段到期 → 破产
                        EconomyModule.onDebtExpired(ctx, f);
                    } else if (f.kind() == FlowKind.MINIGAME) {
                        // 选牙超时 / 托管：服务端代选
                        MinigameModule.onExpired(ctx, f);
                    } else if (f.kind() == FlowKind.RESPONSE && ctx.state().game().cards().effect() != null) {
                        // 道具响应窗到期：托管者自动使用，手动玩家视为不使用
                        CardModule.onResponseExpired(ctx, f);
                    } else {
                        GameModule.closeOverlay(ctx, f.windowId(), CloseReason.EXPIRED);
                    }
                });
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
