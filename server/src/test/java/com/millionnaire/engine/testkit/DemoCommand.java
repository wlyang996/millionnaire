package com.millionnaire.engine.testkit;

import com.millionnaire.engine.core.command.Command;

/** 演示领域命令（测试夹具，非产品规则）。 */
public sealed interface DemoCommand extends Command {

    record Sit(String playerId) implements DemoCommand {
        @Override
        public String actor() {
            return playerId;
        }
    }

    record Stand(String playerId) implements DemoCommand {
        @Override
        public String actor() {
            return playerId;
        }
    }

    /** 房主为指定玩家开启窗口：leadMs 缓冲后开放 {@link DemoDomain#ROUND_MS}。 */
    record OpenRound(String actor, String playerId, long leadMs) implements DemoCommand {
    }

    record PauseRound(String actor) implements DemoCommand {
    }

    record ResumeRound(String actor) implements DemoCommand {
    }

    /** 掷骰，必须携带目标窗口 ID。 */
    record Roll(String actor, long windowId) implements DemoCommand {
    }

    /** 安排一个"全局到时"优先级的停止任务。 */
    record ScheduleStop(String actor, long at) implements DemoCommand {
    }

    /** 查看自己的私有信息（产生 PRIVATE 事件）。 */
    record Peek(String actor) implements DemoCommand {
    }
}
