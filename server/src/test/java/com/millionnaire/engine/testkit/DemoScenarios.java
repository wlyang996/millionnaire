package com.millionnaire.engine.testkit;

import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.engine.StepResult.Outcome;
import com.millionnaire.engine.replay.Scenario;
import com.millionnaire.engine.testkit.DemoCommand.OpenRound;
import com.millionnaire.engine.testkit.DemoCommand.PauseRound;
import com.millionnaire.engine.testkit.DemoCommand.Peek;
import com.millionnaire.engine.testkit.DemoCommand.ResumeRound;
import com.millionnaire.engine.testkit.DemoCommand.Roll;
import com.millionnaire.engine.testkit.DemoCommand.ScheduleStop;
import com.millionnaire.engine.testkit.DemoCommand.Sit;
import com.millionnaire.engine.testkit.DemoCommand.Stand;
import java.util.ArrayList;
import java.util.List;

/** 覆盖拒绝、重复、过期、时间倒退、窗口缓冲、暂停恢复、超时代掷、同刻优先级与私有事件的演示场景。 */
public final class DemoScenarios {
    public static final long SEED = 20261005L;

    private DemoScenarios() {
    }

    public static Scenario full() {
        List<Input> in = new ArrayList<>();
        add(in, 1, 1000, new Sit("a"));                          // a 为房主
        add(in, 2, 1100, new Sit("b"));
        add(in, 3, 1200, new Sit("c"));
        add(in, 4, 1300, new Peek("a"));                         // 私有事件给 a
        add(in, 5, 1400, new Peek("z"));                         // 拒绝 NOT_MEMBER，私发给 z
        add(in, 6, 1500, new Sit("b"));                          // 拒绝 ALREADY_MEMBER
        add(in, 7, 1600, new OpenRound("b", "a", 0));            // 拒绝 NOT_HOST
        add(in, 8, 2000, new OpenRound("a", "b", 1000));         // 窗口1：[3000, 18000)
        add(in, 9, 2500, new Roll("b", 1));                      // 拒绝 WINDOW_NOT_OPEN
        add(in, 10, 4000, new Roll("b", 1));                     // 接受，抽随机数
        add(in, 11, 4100, new Roll("b", 1));                     // 拒绝 NO_ACTIVE_WINDOW（不再抽）
        add(in, 12, 5000, new OpenRound("a", "c", 0));           // 窗口2：[5000, 20000)
        add(in, 13, 6000, new PauseRound("a"));                  // 剩余 14000
        add(in, 14, 9000, new ResumeRound("a"));                 // 截止 23000
        add(in, 15, 30000, new Tick());                          // 23000 到期 → 代掷
        add(in, 16, 30500, new Roll("c", 2));                    // 拒绝 NO_ACTIVE_WINDOW
        add(in, 17, 31000, new OpenRound("a", "a", 0));          // 窗口3：[31000, 46000)
        add(in, 18, 31500, new ScheduleStop("a", 46000));        // 与窗口3同刻到期，全局优先
        add(in, 19, 46000, new Roll("a", 3));                    // 先停止并取消窗口 → 拒绝，不抽随机
        add(in, 20, 47000, new Stand("c"));
        add(in, 20, 47000, new Stand("c"));                      // 同号同内容 → DUPLICATE
        add(in, 21, 48000, new Stand("a"));                      // 房主移交 b
        add(in, 22, 48500, new Sit("d"));
        add(in, 23, 49000, new OpenRound("b", "d", 2000));       // 窗口4
        add(in, 24, 50000, new Stand("d"));                      // 窗口中止并取消计时
        add(in, 25, 53000, new Peek("b"));
        add(in, 26, 52000, new Peek("b"));                       // 低于接收水位 → TIME_REGRESSION
        add(in, 27, 53000, new Peek("b"));                       // 水位未被拉低，等于水位可接受
        add(in, 10, 60000, new Tick());                          // 过期序号 → STALE
        add(in, 28, 60000, new Tick());
        return new Scenario(RuleConfigs.defaultV1(), "room-1", SEED, 0, in);
    }

    public static final List<Outcome> FULL_OUTCOMES = List.of(
            Outcome.ACCEPTED, Outcome.ACCEPTED, Outcome.ACCEPTED, Outcome.ACCEPTED, Outcome.REJECTED,
            Outcome.REJECTED, Outcome.REJECTED, Outcome.ACCEPTED, Outcome.REJECTED, Outcome.ACCEPTED,
            Outcome.REJECTED, Outcome.ACCEPTED, Outcome.ACCEPTED, Outcome.ACCEPTED, Outcome.ACCEPTED,
            Outcome.REJECTED, Outcome.ACCEPTED, Outcome.ACCEPTED, Outcome.REJECTED, Outcome.ACCEPTED,
            Outcome.DUPLICATE, Outcome.ACCEPTED, Outcome.ACCEPTED, Outcome.ACCEPTED, Outcome.ACCEPTED,
            Outcome.ACCEPTED, Outcome.REJECTED, Outcome.ACCEPTED, Outcome.STALE, Outcome.ACCEPTED);

    private static void add(List<Input> in, long seq, long at, Command c) {
        in.add(new Input(seq, at, c));
    }
}
