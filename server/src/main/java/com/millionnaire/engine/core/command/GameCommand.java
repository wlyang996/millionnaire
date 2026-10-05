package com.millionnaire.engine.core.command;

import com.millionnaire.engine.core.state.ControlMode;

/**
 * 对局命令。客户端命令携带目标窗口 ID 或目标局号；系统命令（控制模式、连接判定）都绑定目标局号，
 * 连接判定还携带外层的观测序号（单调递增），过期观测被拒。
 * <p>暂离或托管期间，玩家本人的普通操作（投骰、付费出狱）一律拒绝，必须先 {@link ResumeControl} 明确恢复；
 * 客户端的"恢复并投骰"按钮依次发送 ResumeControl 与 RollDice 两条命令（O18：暂离需明确恢复、托管需手动取消）。
 */
public sealed interface GameCommand extends Command {

    /** 客户端：投骰（投骰窗口）或掷出狱判定骰（狱中判定窗口）。 */
    record RollDice(String actor, long windowId) implements GameCommand {
    }

    /** 客户端：狱中付费出狱（可用现金 ≥ 出狱费）。 */
    record PayBail(String actor, long windowId) implements GameCommand {
    }

    /** 客户端：玩家本人明确恢复手动控制（结束暂离或托管）。 */
    record ResumeControl(String actor, long gameNo) implements GameCommand {
    }

    /** 系统：控制模式变更（暂离 / 托管 / 手动），由外层根据玩家操作转发。 */
    record SetControl(long gameNo, String playerId, ControlMode mode) implements GameCommand, SystemCommand {
    }

    /** 系统：连接判定为疑似断线（15 秒无有效消息）；只能从在线转入。 */
    record ConnectionSuspected(long gameNo, String playerId, long observation) implements GameCommand, SystemCommand {
    }

    /** 系统：连接判定为确认掉线（30 秒无有效消息）；只能从疑似断线转入。 */
    record ConnectionConfirmed(long gameNo, String playerId, long observation) implements GameCommand, SystemCommand {
    }

    /** 系统：玩家重新连接；从疑似断线或确认掉线转入在线。 */
    record Reconnected(long gameNo, String playerId, long observation) implements GameCommand, SystemCommand {
    }
}
