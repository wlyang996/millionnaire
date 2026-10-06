/**
 * 以"截止时间"驱动的倒计时（不用帧计数）。纯 TS，无 cc 依赖。
 * Clock 提供可暂停的虚拟时间：暂停期间 now() 冻结，因此所有基于它的 deadline 一并冻结（演示面板"暂停倒计时"）。
 */
export class Clock {
    private offset = 0; // 累计暂停时长
    private pausedAt = -1;
    constructor(private readonly source: () => number = () => Date.now()) {}

    now(): number {
        const real = this.pausedAt >= 0 ? this.pausedAt : this.source();
        return real - this.offset;
    }

    get paused(): boolean {
        return this.pausedAt >= 0;
    }

    pause(): void {
        if (this.pausedAt < 0) this.pausedAt = this.source();
    }

    resume(): void {
        if (this.pausedAt >= 0) {
            this.offset += this.source() - this.pausedAt;
            this.pausedAt = -1;
        }
    }
}

/** 一个倒计时：deadline 绝对时刻 + 总时长。 */
export class Countdown {
    private deadline = 0;
    private total = 0;
    private firedExpire = false;

    constructor(private readonly clock: Clock) {}

    start(seconds: number): void {
        this.total = seconds * 1000;
        this.deadline = this.clock.now() + this.total;
        this.firedExpire = false;
    }

    /** 直接设置截止时刻（服务端下发 deadline 的接入点）。 */
    setDeadline(deadlineMs: number, totalSeconds: number): void {
        this.deadline = deadlineMs;
        this.total = totalSeconds * 1000;
        this.firedExpire = false;
    }

    /** 延长（如拍卖最后 3 秒恢复到 3 秒，但不超过 capMs 的绝对上限）。 */
    extendTo(remainingMs: number, hardCapDeadline: number): void {
        this.deadline = Math.min(Math.max(this.deadline, this.clock.now() + remainingMs), hardCapDeadline);
        this.firedExpire = false;
    }

    getDeadline(): number {
        return this.deadline;
    }

    remainingMs(): number {
        return Math.max(0, this.deadline - this.clock.now());
    }

    /** 向上取整的剩余秒数，显示用。 */
    remainingSec(): number {
        return Math.ceil(this.remainingMs() / 1000);
    }

    /** 0..1，1 表示刚开始。 */
    fraction(): number {
        return this.total <= 0 ? 0 : Math.min(1, this.remainingMs() / this.total);
    }

    expired(): boolean {
        return this.remainingMs() <= 0;
    }

    /** 只在第一次到期时返回 true。 */
    consumeExpire(): boolean {
        if (!this.firedExpire && this.expired()) {
            this.firedExpire = true;
            return true;
        }
        return false;
    }
}

export function formatMMSS(ms: number): string {
    const s = Math.max(0, Math.ceil(ms / 1000));
    const m = Math.floor(s / 60);
    const r = s % 60;
    return (m < 10 ? '0' : '') + m + ':' + (r < 10 ? '0' : '') + r;
}
