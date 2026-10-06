package com.millionnaire.engine.core.command;

import com.millionnaire.engine.core.state.ControlMode;

/**
 * 对局命令。客户端命令携带目标窗口 ID 或目标局号；系统命令（控制模式、连接判定）都绑定目标局号，
 * 连接判定还携带外层的观测序号（单调递增），过期观测被拒。每个阶段允许哪些命令由 {@code StageTable} 数据表决定。
 * <p><b>产品决定（M1 收尾，取代 M1d 的"默认拒绝"）</b>：暂离 / 托管期间玩家本人发送 {@link RollDice}，
 * 视为"恢复并投骰"——在同一步内先恢复手动控制（ControlChanged MANUAL），再按手动投骰处理。
 * 到期任务仍然优先：若窗口截止或 AUTO_ACT 已在该输入时刻之前到期，内核先执行到期任务，随后的 RollDice 按已关闭的窗口被拒，
 * 被拒的命令不恢复控制。确认掉线（OFFLINE）未被可信重连（Reconnected）清除时不解除自动控制，RollDice 仍被拒。
 * <p>其他手动操作（付费出狱、买地、升级、抵押、赎回等）在暂离 / 托管期间仍被拒绝，需先 {@link ResumeControl}
 * （决定只覆盖"点击投骰"；其余是否同样自动恢复列入 m2-report 待确认）。债务流程的命令例外：债务成立时锁定为手动路径，
 * 债务人本人的应急抵押 / 确认破产 / 继续不受之后的控制模式变化影响。
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

    // ------------------------------------------------------------ M2 经济（均绑定当前窗口）

    /** 客户端：买下当前落点的无主地产或车站（原价，现金须足额）。 */
    record BuyProperty(String actor, long windowId) implements GameCommand {
    }

    /** 客户端：放弃购买（地产保持无主）。 */
    record DeclinePurchase(String actor, long windowId) implements GameCommand {
    }

    /** 客户端：指定拍卖地发起土地拍卖（M5 接入；M2 一律拒绝 NOT_AVAILABLE）。 */
    record StartLandAuction(String actor, long windowId) implements GameCommand {
    }

    /** 客户端：付费把当前落点的自己的普通地产升一级。 */
    record UpgradeProperty(String actor, long windowId) implements GameCommand {
    }

    /** 客户端：不升级。 */
    record SkipUpgrade(String actor, long windowId) implements GameCommand {
    }

    /** 客户端：在银行格、自己回合的操作窗口内按原价 100% 抵押自己的资产（无债务时）。 */
    record BankMortgage(String actor, long windowId, int tile) implements GameCommand {
    }

    /** 客户端：赎回自己的已抵押资产（自己回合的操作窗口内；在银行免手续费，其他位置加 10%）。 */
    record Redeem(String actor, long windowId, int tile) implements GameCommand {
    }

    /** 客户端：结束银行操作。 */
    record FinishBank(String actor, long windowId) implements GameCommand {
    }

    /** 客户端：债务窗口内按应急比例抵押一项资产（筹足即付）。 */
    record EmergencyMortgage(String actor, long windowId, int tile) implements GameCommand {
    }

    /** 客户端：债务窗口第二段弹窗选择"继续抵押"（不延长计时）。 */
    record ContinueDebt(String actor, long windowId) implements GameCommand {
    }

    /** 客户端：债务窗口内确认破产（任何时刻可用）。 */
    record DeclareBankruptcy(String actor, long windowId) implements GameCommand {
    }

    /** 客户端：认输（已二次确认）。流程参与者延后到流程结束；欠款中的债务人认输等同于确认破产（O7）。 */
    record Surrender(String actor, long gameNo) implements GameCommand {
    }
}
