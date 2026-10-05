package com.millionnaire.engine.testkit;

import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.SystemCommand;
import com.millionnaire.engine.core.state.FlowKind;

/** 两个假模块的命令：回合模块（开窗/操作/打断）与申请模块（申请/安全点启动）。 */
public sealed interface FlowTestCommand extends Command {

    /** 回合模块：为 actor 开窗。 */
    record OpenTurn(String actor, long leadMs, long durationMs) implements FlowTestCommand {
    }

    /** 回合模块：在合法打断点开启响应窗（暂停父窗口）。 */
    record OpenResponse(String actor, long durationMs) implements FlowTestCommand {
    }

    /** 任一模块：操作当前窗口（关闭并恢复父窗口）。 */
    record Act(String actor, long windowId) implements FlowTestCommand {
    }

    /** 申请模块：申请拍卖（排队，不抢占）。 */
    record Apply(String actor) implements FlowTestCommand {
    }

    /** 系统：到达安全点，最多启动一个排队申请。 */
    record SafePoint() implements FlowTestCommand, SystemCommand {
    }

    /** 系统：安排全局到时。 */
    record GlobalEndAt(long at) implements FlowTestCommand, SystemCommand {
    }

    /** 仅为类型完整性保留：指定类别开窗。 */
    record OpenKind(String actor, FlowKind kind, long durationMs) implements FlowTestCommand {
    }
}
