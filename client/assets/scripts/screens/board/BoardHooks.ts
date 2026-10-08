/** 棋盘页对外开放的少量动作（详情页等弹窗调用，避免反向依赖 BoardScreen）。棋盘页显示时登记，离开时清空。 */
export const boardHooks: {
    /** 掷出狱判定骰（与出狱判定页的"掷出狱骰"相同：播放骰子动画并发送 RollDice） */
    jailRoll: (() => void) | null;
} = { jailRoll: null };
