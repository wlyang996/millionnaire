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
        this(RuleConfigs.defaultV1(), random, seed);
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

    /** 当前玩家在窗口开放后立即投骰（或掷判定骰）。 */
    public StepResult roll() {
        FlowFrame w = window();
        return send(Math.max(now + 10, w.window().opensAt()), new GameCommand.RollDice(current(), w.windowId()));
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
