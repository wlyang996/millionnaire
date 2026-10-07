package com.millionnaire.engine.core.command;

import com.millionnaire.engine.core.state.ControlMode;

/**
 * 对局命令。客户端命令携带目标窗口 ID 或目标局号；系统命令（控制模式、连接判定）都绑定目标局号，
 * 连接判定还携带外层的观测序号（单调递增），过期观测被拒。每个阶段允许哪些命令由 {@code StageTable} 数据表决定。
 * <p><b>产品决定（2026-10-06）</b>：暂离 / 托管期间本人提交合法业务命令，在同一步先恢复手动控制再执行。
 * 业务集合由 BusinessCommands 的穷尽分类统一维护；只读查看与聊天 / 开麦由外层处理，不解除托管。
 * 到期任务先执行，被拒命令不改变控制；确认掉线必须先经可信 {@link Reconnected}。
 * 已成立的债务路径与原窗口截止不因恢复控制而改变。
 */
public sealed interface GameCommand extends Command {
    record DrawEventCard(String actor, long windowId) implements GameCommand { }
    /** Select an index in the seven-card hand; duplicates remain separately selectable. */
    record DiscardCard(String actor, long windowId, int index) implements GameCommand { }

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

    /**
     * 客户端：在自己的回合窗口内主动使用一张卡（投骰前 / 狱中判定 / 落点后用卡阶段）。目标格一律为当前位置；
     * target 只用于查询（被查询玩家），steps 只用于定点移动（1～6）。拍卖、交易卡另有申请命令。
     */
    record UseCard(String actor, long windowId, com.millionnaire.engine.config.CardType card, String target, int steps)
            implements GameCommand {
    }

    /** 客户端：响应窗内是否使用响应卡（免租 / 房屋保护 / 拒绝购买）。 */
    record RespondCard(String actor, long windowId, boolean use) implements GameCommand {
    }

    /** 客户端：落点后用卡阶段不用卡，直接结束回合。 */
    record FinishTurn(String actor, long windowId) implements GameCommand {
    }

    /** 客户端：虎口拔牙中选一颗未按下的牙（只有当前选牙者、在其选牙窗口内）。 */
    record PickTooth(String actor, long windowId, int tooth) implements GameCommand {
    }

    /** 客户端：认输（已二次确认）。流程参与者延后到流程结束；欠款中的债务人认输等同于确认破产（O7）。 */
    record Surrender(String actor, long gameNo) implements GameCommand {
    }
}
