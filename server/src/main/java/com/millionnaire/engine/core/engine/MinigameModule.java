package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.TileType;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.GameEvent.CloseReason;
import com.millionnaire.engine.core.event.GameEvent.LandingStepEntered;
import com.millionnaire.engine.core.event.GameEvent.MinigameEnded;
import com.millionnaire.engine.core.event.GameEvent.MinigameStarted;
import com.millionnaire.engine.core.event.GameEvent.ToothPicked;
import com.millionnaire.engine.core.event.GameEvent.TurnStageEntered;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.Continuation;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.FlowOrigin;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.GameView;
import com.millionnaire.engine.core.state.LandingResult;
import com.millionnaire.engine.core.state.LandingState;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.core.state.MinigameState;
import com.millionnaire.engine.core.state.PlayerState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.random.DrawPoint;
import java.util.ArrayList;
import java.util.List;

/**
 * 虎口拔牙（requirements 第 13 节、第 5 节奖励、已采纳默认值 #15）：
 * <ul>
 *   <li>游戏区落点的 MINIGAME 任务启动；不足两名存活者不启动（任务不生成）。回合进入 AWAITING_FLOW，结束后按 ResumeLanding 继续；</li>
 *   <li>全部存活玩家参加，从触发者起按行动顺序轮流选未按下的牙；牙齿数 = 人数 × 2，危险牙由 R10 抽取、结束前保密；</li>
 *   <li>每次选牙一个 MINIGAME 覆盖窗口（所有者为选牙者），手动玩家 10 秒；托管 / 暂离 / 确认掉线 / 已申请认输者按自动动作延时代选；
 *       超时同样由服务端在剩余牙中等概率代选（R11）；</li>
 *   <li>按到危险牙者为输家，立即结束；其余参与者各获系统奖励 500，输家不扣钱。期间不接受主动用卡，认输延后到结束后清算。</li>
 * </ul>
 * 小游戏选牙不计棋盘回合，全局时钟照常运行；全局到时后已触发的小游戏照常完成（O16）。
 */
final class MinigameModule {
    static final String REWARD = "MINIGAME_REWARD";
    static final String TAG = "MINIGAME";

    private MinigameModule() {
    }

    /** 是否启动小游戏（决策与演化共用）：游戏区且至少两名存活者。 */
    static boolean eligible(RuleConfig config, GameState g, int tile) {
        return LobbyModule.board(config, g.settings()).tiles().get(tile).type() == TileType.GAME_ZONE && g.alive().size() >= 2;
    }

    /** 参与者：全部存活玩家，从触发者起按行动顺序。 */
    static List<String> participants(GameState g, String trigger) {
        List<PlayerState> ps = g.players();
        int start = 0;
        for (int i = 0; i < ps.size(); i++) {
            if (ps.get(i).playerId().equals(trigger)) {
                start = i;
            }
        }
        List<String> out = new ArrayList<>();
        for (int k = 0; k < ps.size(); k++) {
            PlayerState p = ps.get((start + k) % ps.size());
            if (p.alive()) {
                out.add(p.playerId());
            }
        }
        return out;
    }

    /** 落点推进器执行 MINIGAME 任务：进入等待流程，抽危险牙，开第一个选牙窗口（含尚未播完的移动动画）。 */
    static void start(DecisionContext<SessionState> ctx, long leadMs) {
        GameState g = ctx.state().game();
        LandingState l = g.turn().landing();
        ctx.emit(new LandingStepEntered(l.landingId(), LandingStep.MINIGAME, 0, l.cursor()));
        ctx.emit(new TurnStageEntered(TurnStage.AWAITING_FLOW, 0,
                new Continuation.ResumeLanding(g.turn().turnNo(), l.landingId()), Math.addExact(ctx.now(), leadMs)));
        List<String> participants = participants(g, g.turn().currentPlayer());
        int teeth = Math.multiplyExact(participants.size(), 2);
        ctx.draw(DrawPoint.DANGER_TOOTH, teeth);
        ctx.emit(new MinigameStarted(l.landingId(), l.cursor(), g.turn().currentPlayer(), participants, teeth));
        openPick(ctx, leadMs);
    }

    /** 为当前选牙者开窗口：手动玩家按选牙时限；需要代选的玩家只等自动动作延时。 */
    private static void openPick(DecisionContext<SessionState> ctx, long leadMs) {
        GameState g = ctx.state().game();
        MinigameState m = g.minigame();
        String picker = m.picker();
        long duration = automatic(g, picker)
                ? Math.min(ctx.config().timing().autoActDelayMs(), ctx.config().timing().toothPickMs())
                : ctx.config().timing().toothPickMs();
        FlowOrigin origin = new FlowOrigin(FlowOrigin.Kind.MINIGAME, g.turn().turnNo(), m.landingId(), m.cursor(), picker);
        if (!(GameModule.openSourcedOverlay(ctx, FlowKind.MINIGAME, picker, leadMs, duration, TAG, origin)
                instanceof FlowCoordinator.Opened.Window)) {
            throw new IllegalStateException("tooth picks always have a positive duration");
        }
    }

    /** 由服务端代选：托管 / 暂离 / 确认掉线，或已申请认输（延后期间按托管代选，#15）。 */
    static boolean automatic(GameState g, String playerId) {
        return g.player(playerId).map(PlayerState::automated).orElse(true) || g.pendingSurrenders().contains(playerId);
    }

    static RejectionCode decide(DecisionContext<SessionState> ctx, GameCommand.PickTooth c) {
        GameState g = ctx.state().game();
        MinigameState m = g.minigame();
        if (c.actor() == null || g.player(c.actor()).isEmpty()) {
            return RejectionCode.NOT_MEMBER;
        }
        if (!g.player(c.actor()).orElseThrow().alive()) {
            return RejectionCode.NOT_ALIVE;
        }
        if (m == null) {
            return RejectionCode.NO_ACTIVE_WINDOW;
        }
        FlowFrame top = g.flow().top().orElse(null);
        if (top == null || top.kind() != FlowKind.MINIGAME) {
            return RejectionCode.NO_ACTIVE_WINDOW;
        }
        RejectionCode why = FlowCoordinator.checkWindow(g.flow(), c.windowId(), c.actor(), ctx.now());
        if (why != null) {
            return why;
        }
        if (c.tooth() < 0 || c.tooth() >= m.teeth() || m.picks().contains(c.tooth())) {
            return RejectionCode.INVALID_ARGUMENT;
        }
        FlowCoordinator.close(ctx, GameModule.FLOW, top.windowId(), CloseReason.ACTED);
        pick(ctx, c.actor(), c.tooth(), false);
        return null;
    }

    /** 选牙窗口到期：服务端在剩余牙中等概率代选。 */
    static void onExpired(DecisionContext<SessionState> ctx, FlowFrame frame) {
        MinigameState m = ctx.state().game().minigame();
        if (m == null || !frame.owner().equals(m.picker())) {
            throw new IllegalStateException("minigame window without its pick");
        }
        FlowCoordinator.close(ctx, GameModule.FLOW, frame.windowId(), CloseReason.EXPIRED);
        List<Integer> remaining = m.remaining();
        int tooth = remaining.get(ctx.draw(DrawPoint.AUTO_TOOTH, remaining.size()));
        pick(ctx, m.picker(), tooth, true);
    }

    private static void pick(DecisionContext<SessionState> ctx, String player, int tooth, boolean auto) {
        MinigameState m = ctx.state().game().minigame();
        ctx.emit(new ToothPicked(m.landingId(), player, tooth, auto));
        if (tooth != m.danger()) {
            openPick(ctx, 0);
            return;
        }
        ctx.emit(new MinigameEnded(m.landingId(), player, m.danger(), ctx.config().economy().miniGameWinReward()));
        // 流程全部结束：延后认输批次 → 续接 ResumeLanding（落点结束、回合结束）
        TurnModule.afterOverlay(ctx);
    }

    // ================================================================ 演化

    static boolean handles(GameEvent e) {
        return e instanceof MinigameStarted || e instanceof ToothPicked || e instanceof MinigameEnded;
    }

    static GameState evolve(GameState g, GameEvent event, Draws draws, RuleConfig rules) {
        var t = g.turn();
        var l = t.landing();
        MinigameState m = g.minigame();
        return switch (event) {
            case MinigameStarted e -> {
                check(m == null && l != null && l.landingId() == e.landingId() && l.cursor() == e.cursor()
                        && l.step() == LandingStep.MINIGAME && l.currentTask() == LandingStep.MINIGAME
                        && t.stage() == TurnStage.AWAITING_FLOW && g.flow().frames().isEmpty() && g.debt() == null
                        && e.trigger().equals(t.currentPlayer()) && eligible(rules, g, l.tile())
                        && e.participants().equals(participants(g, e.trigger()))
                        && e.teeth() == Math.multiplyExact(e.participants().size(), 2), "minigame start mismatch");
                int danger = draws.take(DrawPoint.DANGER_TOOTH, e.teeth());
                yield g.withMinigame(new MinigameState(e.landingId(), e.cursor(), e.trigger(), e.participants(), e.teeth(),
                        danger, List.of()));
            }
            case ToothPicked e -> {
                check(m != null && m.landingId() == e.landingId() && e.playerId().equals(m.picker())
                        && e.tooth() >= 0 && e.tooth() < m.teeth() && !m.picks().contains(e.tooth())
                        && g.flow().frames().isEmpty(), "tooth pick mismatch");
                if (e.auto()) {
                    List<Integer> remaining = m.remaining();
                    check(remaining.get(draws.take(DrawPoint.AUTO_TOOTH, remaining.size())) == e.tooth(),
                            "automatic pick does not match its draw");
                }
                yield g.withMinigame(m.picked(e.tooth()));
            }
            case MinigameEnded e -> {
                check(m != null && m.landingId() == e.landingId() && !m.picks().isEmpty()
                        && m.picks().getLast() == m.danger() && e.danger() == m.danger()
                        && e.loser().equals(m.participants().get((m.picks().size() - 1) % m.participants().size()))
                        && e.reward() == rules.economy().miniGameWinReward() && g.flow().frames().isEmpty()
                        && l != null && l.landingId() == m.landingId() && l.cursor() == m.cursor(), "minigame end mismatch");
                Ledger ledger = g.ledger();
                for (String p : m.participants()) {
                    if (!p.equals(e.loser()) && e.reward() > 0) {
                        ledger = ledger.transfer(Ledger.SYSTEM, p, e.reward(), REWARD, "landing-" + m.landingId());
                    }
                }
                GameState paid = g.withLedger(ledger).withMinigame(null);
                yield paid.withTurn(t.withLanding(LandingRules.consume(rules, paid, l, LandingResult.PLAYED)));
            }
            default -> throw new IllegalStateException("not a minigame event: " + event);
        };
    }

    // ================================================================ 校验与投影

    /** 进行中的小游戏必须绑定当前落点的 MINIGAME 任务、回合等待流程、栈顶为当前选牙者的窗口。 */
    static void validate(GameState g, RuleConfig config) {
        MinigameState m = g.minigame();
        boolean frame = g.flow().frames().stream().anyMatch(f -> f.kind() == FlowKind.MINIGAME);
        if (m == null) {
            LobbyModule.expect(!frame, "a minigame window without a minigame");
            return;
        }
        var t = g.turn();
        var l = t.landing();
        FlowFrame top = g.flow().top().orElse(null);
        LobbyModule.expect(l != null && l.landingId() == m.landingId() && l.cursor() == m.cursor()
                && l.step() == LandingStep.MINIGAME && t.stage() == TurnStage.AWAITING_FLOW && g.debt() == null
                && m.trigger().equals(t.currentPlayer()) && !m.participants().isEmpty()
                && m.participants().getFirst().equals(m.trigger())
                && m.participants().stream().distinct().count() == m.participants().size()
                && m.participants().stream().allMatch(p -> g.player(p).map(PlayerState::alive).orElse(false))
                && m.participants().size() >= 2 && m.teeth() == m.participants().size() * 2
                && m.danger() >= 0 && m.danger() < m.teeth()
                && m.picks().stream().allMatch(p -> p != null && p >= 0 && p < m.teeth() && p != m.danger())
                && m.picks().stream().distinct().count() == m.picks().size()
                && g.flow().frames().size() == 1 && top != null && top.kind() == FlowKind.MINIGAME
                && top.owner().equals(m.picker()), "minigame invalid");
    }

    static boolean resting(GameState g, LandingState l) {
        MinigameState m = g.minigame();
        return g.turn().stage() == TurnStage.AWAITING_FLOW && m != null && m.landingId() == l.landingId() && m.cursor() == l.cursor();
    }

    /** 公开投影：不含危险牙。 */
    static GameView.PublicMinigame view(GameState g) {
        MinigameState m = g.minigame();
        if (m == null) {
            return null;
        }
        long windowId = g.flow().top().filter(f -> f.kind() == FlowKind.MINIGAME).map(FlowFrame::windowId).orElse(0L);
        return new GameView.PublicMinigame(m.landingId(), m.trigger(), m.participants(), m.teeth(), m.picks(), m.picker(), windowId);
    }

    private static void check(boolean condition, String message) {
        LobbyModule.check(condition, message);
    }
}
