package com.millionnaire.engine.core.command;

/** 开局 / 回房边界命令（M1 骨架）。 */
public sealed interface SessionCommand extends Command {

    /** 房主开局：至少 minPlayersToStart 人且全员准备。 */
    record StartGame(String actor) implements SessionCommand {
    }

    /**
     * 结束对局并回到大厅。M0/M1 骨架中只接受系统输入（actor 为 null，由外层或管理端投递）；
     * M1 实现规则驱动的结束（全局到时、只剩一人）后，此命令只作为管理端中止入口保留。
     */
    record EndGame(String reason) implements SessionCommand {
        @Override
        public String actor() {
            return null;
        }
    }
}
