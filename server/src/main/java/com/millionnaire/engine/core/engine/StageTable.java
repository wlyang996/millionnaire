package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.core.state.TurnStage;
import java.util.List;

/**
 * 阶段数据表（M2 P1）：每个可等待的决策点——允许的命令、时限、超时默认动作、自动策略、是否紧随安全点、DRAINING 权限。
 * 回合模块与经济模块按此表判定命令与执行超时 / 自动动作，不再在代码里写"不是 PRE_ROLL 就当监狱"之类的分支。
 * 自动策略区分控制模式（暂离 / 托管：足额就买、就升；opus 4.5 与已采纳默认值 #17）与仅确认掉线（掉线：不买不升级，requirements 第 15 节）。
 */
final class StageTable {
    private StageTable() {
    }

    /** 决策点。 */
    enum Point {
        /** 狱中判定窗口（JAIL_DECISION）。 */
        JAIL,
        /** 投骰窗口（PRE_ROLL）。 */
        ROLL,
        /** 落点：买 / 放弃。 */
        BUY,
        /** 落点：升级 / 跳过（含买后立即升级）。 */
        UPGRADE,
        /** 落点：银行操作。 */
        BANK,
        /** 落点：无推进器的普通等待（测试与占位）。 */
        WAIT,
        /** 债务覆盖窗口（两段）。 */
        DEBT, EVENT_DRAW, DISCARD,
        /** 落点：缴租前的免租响应（持有免租卡时）。 */
        RENT_RESPONSE
    }

    /** 时限来源。 */
    enum Duration {
        /** 投骰操作时间（房间设置，出狱后沿用剩余）。 */
        ROLL,
        /** 落点决策 15 秒。 */
        DECISION,
        /** 债务每段 30 秒。 */
        DEBT_SEGMENT, DISCARD,
        /** 响应窗 10 秒。 */
        RESPONSE
    }

    /** 超时或自动执行的动作。 */
    enum Action {
        ROLL, BUY_IF_AFFORDABLE, DECLINE, UPGRADE_IF_AFFORDABLE, SKIP, FINISH, NEXT_SEGMENT_OR_BANKRUPT, DRAW_EVENT, DISCARD_NEW,
        /** 使用响应卡（托管 / 掉线 / 暂离持卡者）。 */
        USE_RESPONSE,
        /** 不使用响应卡（手动玩家超时）。 */
        DECLINE_RESPONSE
    }

    /**
     * 一行规则。safePointBefore：该窗口紧随回合起始安全点（排队流程已在此之前启动）；drainingAllowed：全局到时后仍可进行；
     * preemptible：其上能否直接开启覆盖流程（N3：落点决策窗口与债务窗口不可抢占；投骰前可被攻击类流程覆盖）。
     */
    record Rule(Point point, List<Class<? extends GameCommand>> commands, Duration duration, Action onTimeout,
                Action hostedPolicy, Action offlinePolicy, boolean safePointBefore, boolean drainingAllowed, List<FlowKind> preemptible) {
        boolean preemptible(FlowKind kind) { return preemptible.contains(kind); }
        boolean allows(GameCommand command) {
            return commands.stream().anyMatch(c -> c.isInstance(command));
        }
    }

    static final List<Rule> RULES = List.of(
            new Rule(Point.JAIL, List.of(GameCommand.RollDice.class, GameCommand.PayBail.class, GameCommand.Redeem.class,
                    GameCommand.BankMortgage.class, GameCommand.UseCard.class), Duration.ROLL, Action.ROLL, Action.ROLL, Action.ROLL, true, false, List.of()),
            new Rule(Point.ROLL, List.of(GameCommand.RollDice.class, GameCommand.Redeem.class, GameCommand.BankMortgage.class,
                    GameCommand.UseCard.class),
                    Duration.ROLL, Action.ROLL, Action.ROLL, Action.ROLL, true, false, List.of(FlowKind.ATTACK)),
            // 待确认默认 4：本人普通操作窗口均可赎回（含买 / 升级窗口，不刷新截止；DRAINING 后仍按 O16 拒绝）
            new Rule(Point.BUY, List.of(GameCommand.BuyProperty.class, GameCommand.DeclinePurchase.class,
                    GameCommand.StartLandAuction.class, GameCommand.Redeem.class), Duration.DECISION, Action.DECLINE, Action.BUY_IF_AFFORDABLE,
                    Action.DECLINE, false, true, List.of()),
            new Rule(Point.UPGRADE, List.of(GameCommand.UpgradeProperty.class, GameCommand.SkipUpgrade.class,
                    GameCommand.Redeem.class),
                    Duration.DECISION, Action.SKIP, Action.UPGRADE_IF_AFFORDABLE, Action.SKIP, false, true, List.of()),
            new Rule(Point.BANK, List.of(GameCommand.BankMortgage.class, GameCommand.Redeem.class, GameCommand.FinishBank.class),
                    Duration.DECISION, Action.FINISH, Action.FINISH, Action.FINISH, false, false, List.of()),
            new Rule(Point.WAIT, List.of(), Duration.DECISION, Action.FINISH, Action.FINISH, Action.FINISH, false, true, List.of(FlowKind.ATTACK)),
            new Rule(Point.DEBT, List.of(GameCommand.EmergencyMortgage.class, GameCommand.ContinueDebt.class,
                    GameCommand.DeclareBankruptcy.class), Duration.DEBT_SEGMENT, Action.NEXT_SEGMENT_OR_BANKRUPT,
                    Action.NEXT_SEGMENT_OR_BANKRUPT, Action.NEXT_SEGMENT_OR_BANKRUPT, false, true, List.of()),
            new Rule(Point.EVENT_DRAW, List.of(GameCommand.DrawEventCard.class), Duration.DECISION,
                    Action.DRAW_EVENT, Action.DRAW_EVENT, Action.DRAW_EVENT, false, true, List.of()),
            new Rule(Point.DISCARD, List.of(GameCommand.DiscardCard.class), Duration.DISCARD,
                    Action.DISCARD_NEW, Action.DISCARD_NEW, Action.DISCARD_NEW, false, true, List.of()),
            // O4：只在持卡时弹出；手动超时不使用，托管 / 掉线 / 暂离自动使用
            new Rule(Point.RENT_RESPONSE, List.of(GameCommand.RespondCard.class), Duration.RESPONSE,
                    Action.DECLINE_RESPONSE, Action.USE_RESPONSE, Action.USE_RESPONSE, false, true, List.of()));

    static Rule rule(Point point) {
        return RULES.stream().filter(r -> r.point() == point).findFirst().orElseThrow();
    }

    /** 当前回合窗口对应的决策点（只对绑定 TURN 窗口的阶段有意义）。 */
    static Point turnPoint(GameState g) {
        TurnStage stage = g.turn().stage();
        return switch (stage) {
            case JAIL_DECISION -> Point.JAIL;
            case PRE_ROLL -> Point.ROLL;
            case LANDING -> g.turn().landing() == null ? Point.WAIT : landingPoint(g.turn().landing().step());
            case NONE, AWAITING_FLOW -> throw new IllegalStateException("no turn window in stage " + stage);
        };
    }

    static Point landingPoint(LandingStep step) {
        Point point = LandingRules.rule(step).point();
        if (point == null) { throw new IllegalStateException(step + " has no landing window"); }
        return point;
    }

    static long durationMs(RuleConfig config, GameState g, Duration d) {
        return switch (d) {
            case ROLL -> Math.multiplyExact((long) g.settings().rollSeconds(), 1000L);
            case DECISION -> config.timing().decisionWindowMs();
            case DEBT_SEGMENT -> config.timing().debtSegmentMs();
            case DISCARD -> config.timing().discardWindowMs();
            case RESPONSE -> config.timing().responseWindowMs();
        };
    }
}
