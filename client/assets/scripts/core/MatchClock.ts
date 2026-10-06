/**
 * 本局剩余时间警示（顶部本局倒计时，不是每回合操作时限）。纯函数，无 cc 依赖。
 * 以显示的整秒为准（向上取整）：>=600 正常；180..599 红色常亮；1..179 红色闪烁；0 红色常亮。
 */
import { Theme } from './Theme';

export type MatchClockState = 'normal' | 'warning' | 'urgent' | 'zero';

export function matchClockState(remainingSec: number): MatchClockState {
    if (remainingSec >= 600) return 'normal';
    if (remainingSec >= 180) return 'warning';
    if (remainingSec > 0) return 'urgent';
    return 'zero';
}

export function remainingSecFromMs(ms: number): number {
    return Math.max(0, Math.ceil(ms / 1000));
}

/** 闪烁透明度：周期 blinkMs，前半周期 100%，后半周期 blinkLow（35%）。非闪烁状态恒为 1。 */
export function matchClockOpacity(state: MatchClockState, nowMs: number): number {
    if (state !== 'urgent') return 1;
    const p = ((nowMs % Theme.anim.blinkMs) + Theme.anim.blinkMs) % Theme.anim.blinkMs;
    return p < Theme.anim.blinkMs / 2 ? 1 : Theme.anim.blinkLow;
}
