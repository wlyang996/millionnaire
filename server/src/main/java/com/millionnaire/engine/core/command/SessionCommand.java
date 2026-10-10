package com.millionnaire.engine.core.command;

/** 开局 / 回房边界命令。 */
public sealed interface SessionCommand extends Command {
    sealed interface TeamCommand extends SessionCommand { }
    /** 0 selects individual play; a positive value selects the configured number per team. */
    record ConfigureTeams(String actor, int teamSize) implements TeamCommand { }
    /** Real people choose their own team. */
    record ChooseTeam(String actor, com.millionnaire.engine.core.state.TeamState.TeamId teamId) implements TeamCommand { }
    /** Host may assign only a server-identified bot, never another real person. */
    record AssignBotTeam(String actor, String target, com.millionnaire.engine.core.state.TeamState.TeamId teamId) implements TeamCommand { }
    /** 0 selects an ordinary game; changing series rules clears every ready flag. */
    record ConfigureSeries(String actor, int totalGames) implements SessionCommand { }
    /** Host may end a series between games, retaining every already awarded point. */
    record EndSeries(String actor, long seriesNo) implements SessionCommand { }

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
