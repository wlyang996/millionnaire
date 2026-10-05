package com.millionnaire.engine.core.command;

/** 开局 / 回房边界命令。 */
public sealed interface SessionCommand extends Command {

    /** 客户端命令：房主开局（至少 minPlayersToStart 人且全员准备）。 */
    record StartGame(String actor) implements SessionCommand {
    }

    /**
     * 系统命令：结束第 expectedGameNo 局并回到大厅。局号不匹配（延迟投递的旧请求）正常拒绝。
     * M1 实现规则驱动的结束（全局到时 DRAINING、只剩一人）后，它只作为管理端中止入口保留。
     */
    record EndGame(long expectedGameNo, String reason) implements SessionCommand, SystemCommand {
    }
}
