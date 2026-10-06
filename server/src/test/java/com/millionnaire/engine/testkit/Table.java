package com.millionnaire.engine.testkit;

import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.command.SessionCommand;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.engine.Engine;
import com.millionnaire.engine.core.engine.SessionDomain;
import com.millionnaire.engine.core.engine.StepResult;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.random.RandomSourceFactory;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.replay.Scenario;
import java.util.ArrayList;
import java.util.List;

/** 对局测试台：驱动生产 SessionDomain，记录全部输入与事件（可转成可回放的 Scenario）。 */
public final class Table {
    public final RuleConfig config;
    public final Engine<SessionState> engine;
    public final long seed;
    public EngineState state;
    public long seq;
    public long now;
    public final List<Event> log = new ArrayList<>();
    public final List<Input> inputs = new ArrayList<>();
    public Table(RandomSourceFactory random, long seed) {
        this(TestBoards.legacyV1(), random, seed);
    }

    public Table(RuleConfig config, RandomSourceFactory random, long seed) {
        this.config = config;
        this.seed = seed;
        this.engine = new Engine<>(config, SessionDomain.INSTANCE, random);
        StepResult g = engine.create("table", seed, 0);
        state = g.state();
        log.addAll(g.events());
    }

    public static Table production(long seed) {
        return new Table(XoshiroLemireV1.INSTANCE, seed);
    }

    public StepResult send(long at, Command c) {
        now = Math.max(now, at);
        Input in = new Input(++seq, at, c);
        inputs.add(in);
        StepResult r = engine.step(state, in);
        state = r.state();
        log.addAll(r.events());
        return r;
    }

    public StepResult send(Command c) {
        return send(now + 10, c);
    }

    /** 加入 p1..pN、（可选）切到 50 格并设置局时、全员准备、房主开局。 */
    public Table start(int players, String boardId, EndMode mode, int timeLimitMinutes) {
        send(new RoomCommand.Join("p1", "P1"));
        RoomSettings s = new RoomSettings(boardId, 3000, mode, timeLimitMinutes, 15);
        if (!s.equals(session().lobby().settings())) {
            send(new RoomCommand.ChangeSettings("p1", s));
        }
        for (int i = 2; i <= players; i++) {
            send(new RoomCommand.Join("p" + i, "P" + i));
        }
        for (int i = 1; i <= players; i++) {
            send(new RoomCommand.SetReady("p" + i, true));
        }
        send(new SessionCommand.StartGame("p1"));
        return this;
    }

    public Table start(int players) {
        return start(players, RuleConfigs.BOARD_30, EndMode.TIME_LIMIT, 30);
    }

    public SessionState session() {
        return (SessionState) state.domain();
    }

    public GameState game() {
        return session().game();
    }

    public String current() {
        return game().turn().currentPlayer();
    }

    public FlowFrame window() {
        return game().flow().frames().get(game().flow().frames().size() - 1);
    }

    public long windowId() {
        return game().turn().windowId();
    }

    /**
     * <b>严格投骰</b>（M2b E10，经济测试统一使用）：当前必须是投骰 / 狱中判定窗口，否则测试失败；不自动处理任何落点窗口。
     */
    public StepResult rollOnly() {
        var stage = game().turn().stage();
        if (stage != com.millionnaire.engine.core.state.TurnStage.PRE_ROLL
                && stage != com.millionnaire.engine.core.state.TurnStage.JAIL_DECISION) {
            throw new IllegalStateException("rollOnly() in stage " + stage + " (landing " + game().turn().landing() + ")");
        }
        FlowFrame w = window();
        return send(Math.max(now + 10, w.window().opensAt()), new GameCommand.RollDice(current(), w.windowId()));
    }

    /** M1/M2 scenarios isolated from event effects: resolve CARD/discard decisions, never buy/upgrade/bank decisions. */
    public StepResult rollThenResolveEvent() {
        int start = log.size();
        StepResult r = rollOnly();
        while (session().inGame() && game().turn().landing() != null
                && (game().turn().landing().step() == com.millionnaire.engine.core.state.LandingStep.EVENT
                    || game().turn().landing().step() == com.millionnaire.engine.core.state.LandingStep.DISCARD)) { pass(); }
        return new StepResult(state, new ArrayList<>(log.subList(start, log.size())), r.outcome(), r.rejection());
    }

    /** 明确的"先完成当前落点（手动放弃 / 不升级 / 结束银行）再投骰"；不处理投骰后的新落点。 */
    public StepResult passThenRoll() {
        while (session().inGame() && game().turn().stage() == com.millionnaire.engine.core.state.TurnStage.LANDING) {
            pass();
        }
        return rollOnly();
    }

    /**
     * M1 风格的"走完一个回合"：先完成当前落点，再投骰，再按手动放弃完成投骰产生的落点决策（自动玩家的落点不处理）。
     * 返回值合并了全部步骤：state 为最终状态（等于 {@link #state}），events 为这些步骤的全部事件，outcome / rejection 取投骰那一步。
     * 经济规则测试不要用它（会自动放弃落点），改用 {@link #rollOnly()}。
     */
    public StepResult roll() {
        int from = log.size();
        StepResult r = passThenRoll();
        while (session().inGame() && game().turn().stage() == com.millionnaire.engine.core.state.TurnStage.LANDING
                && !game().player(current()).orElseThrow().automated()) {
            pass();
        }
        List<Event> all = new ArrayList<>(log.subList(from, log.size()));
        return new StepResult(state, all, r.outcome(), r.rejection());
    }

    /** 当前落点决策窗口的"手动放弃"：放弃购买、不升级、结束银行。 */
    public StepResult pass() {
        FlowFrame w = window();
        long at = Math.max(now + 10, w.window().opensAt());
        var landing = game().turn().landing();
        if (game().player(current()).orElseThrow().automated()) {
            long auto = game().turn().autoTaskId();
            long due = auto != 0 ? state.timers().find(auto).orElseThrow().dueAt() : w.window().deadline();
            return tick(Math.max(now, due));
        }
        Command c = landing == null ? new Tick() : switch (landing.step()) {
            case BUY -> new GameCommand.DeclinePurchase(current(), w.windowId());
            case UPGRADE -> new GameCommand.SkipUpgrade(current(), w.windowId());
            case BANK -> new GameCommand.FinishBank(current(), w.windowId());
            case EVENT -> new GameCommand.DrawEventCard(current(), w.windowId());
            case DISCARD -> new GameCommand.DiscardCard(current(), w.windowId(), landing.event().newCardIndex());
            default -> throw new IllegalStateException("no decision window for " + landing.step());
        };
        return c instanceof Tick ? tick(w.window().deadline()) : send(at, c);
    }

    /** 在当前回合窗口开放后发送一条带窗口 ID 的命令。 */
    public StepResult act(java.util.function.LongFunction<Command> command) {
        FlowFrame w = window();
        return send(Math.max(now + 10, w.window().opensAt()), command.apply(w.windowId()));
    }

    public StepResult tick(long at) {
        return send(at, new Tick());
    }

    public int position(String player) {
        return game().player(player).orElseThrow().position();
    }

    public long cash(String player) {
        return game().ledger().cash(player);
    }

    public Scenario scenario() {
        return new Scenario(config, "table", seed, 0, inputs);
    }

    /** 脚本步骤简写。 */
    public static List<ScriptedRandom.Step> steps(DrawPoint point, int bound, int... values) {
        List<ScriptedRandom.Step> out = new ArrayList<>();
        for (int v : values) {
            out.add(ScriptedRandom.step(point, bound, v));
        }
        return out;
    }

    /** 骰子点数 1..6 的脚本（内部值 = 点数 - 1）。 */
    public static List<ScriptedRandom.Step> dice(DrawPoint point, int... faces) {
        List<ScriptedRandom.Step> out = new ArrayList<>();
        for (int f : faces) {
            out.add(ScriptedRandom.step(point, 6, f - 1));
        }
        return out;
    }

    /** 开局抽数脚本（数字 1..100）。 */
    public static List<ScriptedRandom.Step> order(int... numbers) {
        List<ScriptedRandom.Step> out = new ArrayList<>();
        for (int n : numbers) {
            out.add(ScriptedRandom.step(DrawPoint.ORDER_NUMBER, 100, n - 1));
        }
        return out;
    }

    /** 开局发牌脚本：players 人各 2 张，全部为权重区间 [0,120) 的第一种牌（ROADBLOCK）。 */
    public static List<ScriptedRandom.Step> deal(int players) {
        List<ScriptedRandom.Step> out = new ArrayList<>();
        for (int i = 0; i < players * 2; i++) {
            out.add(ScriptedRandom.step(DrawPoint.INITIAL_CARD, 1000, 0));
        }
        return out;
    }

    @SafeVarargs
    public static List<ScriptedRandom.Step> script(List<ScriptedRandom.Step>... parts) {
        List<ScriptedRandom.Step> out = new ArrayList<>();
        for (List<ScriptedRandom.Step> p : parts) {
            out.addAll(p);
        }
        return out;
    }
}
