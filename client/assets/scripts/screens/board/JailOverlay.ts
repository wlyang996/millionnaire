/**
 * 入狱动画（所有观察者同步观看，约 2.6 秒，不可交互、不挡后续操作）：
 * 暗场 → 监狱近景弹出（招牌上写"某某 入狱"）→ 一排铁栏杆从屏幕上方砸下、回弹并轻微震屏 → 淡出。
 * 状态（谁、何时开始）由 BoardScreen 保存，页面重建后按时间戳续播。
 */
import { Node } from 'cc';
import { animMs, Theme } from '../../core/Theme';
import { art } from '../../ui/Art';
import { fillRR, gfx, line, mk, place, setOpacity, text } from '../../ui/Kit';

/** 当前动画倍速（快速动画为 2）。 */
const speed = (): number => 1000 / animMs(1000);
export const JAIL_SHOW_MS = 2600;

const POP_MS = 380;
const DROP_AT = 520;
const DROP_MS = 300;
const FADE_MS = 360;

const IMG_W = 560;
const IMG_H = Math.round(IMG_W * 1207 / 1303);
const IMG_X = (Theme.W - IMG_W) / 2;
const IMG_Y = 380;
const BARS_Y = IMG_Y + 40;
const BARS_H = IMG_H - 20;

export class JailOverlay {
    readonly root: Node;
    private readonly scene: Node;
    private readonly bars: Node;

    constructor(parent: Node, name: string, private readonly start: number) {
        this.root = mk(parent, 'JailOverlay', 0, 0, Theme.W, Theme.H);
        fillRR(gfx(mk(this.root, 'Dim', 0, 0, Theme.W, Theme.H)), 0, 0, Theme.W, Theme.H, 0, '#10141CB0');
        // 监狱近景（以中心缩放弹出）：外层节点放在图片中心，内层按左上偏移回去
        this.scene = mk(this.root, 'Scene', IMG_X + IMG_W / 2, IMG_Y + IMG_H / 2, 0, 0);
        const inner = mk(this.scene, 'Inner', -IMG_W / 2, -IMG_H / 2, IMG_W, IMG_H);
        if (!art(inner, 'scene_jail_closeup', 0, 0, IMG_W, IMG_H)) {
            fillRR(gfx(inner), 60, 60, IMG_W - 120, IMG_H - 120, 30, '#8A929C');
        }
        // 招牌位置（原图 400..980 × 110..320）
        const k = IMG_W / 1303;
        text(inner, name, 410 * k, 120 * k, 560 * k, 110 * k, 34, '#5A3A1A', { bold: true });
        text(inner, '入狱！', 410 * k, 215 * k, 560 * k, 95 * k, 30, '#B23A2E', { bold: true });
        // 铁栏杆：全宽一排，从上方砸下
        this.bars = mk(this.root, 'Bars', 0, BARS_Y, Theme.W, BARS_H);
        const g = gfx(this.bars);
        fillRR(g, 30, 0, Theme.W - 60, 26, 8, '#3A404C');
        fillRR(g, 30, 4, Theme.W - 60, 6, 3, '#8E98A6');
        fillRR(g, 30, BARS_H - 26, Theme.W - 60, 26, 8, '#3A404C');
        for (let i = 0; i < 9; i++) {
            const x = 60 + i * ((Theme.W - 120) / 8);
            line(g, x, 10, x, BARS_H - 10, '#2E333D', 16);
            line(g, x - 4, 14, x - 4, BARS_H - 14, '#B9C2CE', 4);
        }
        text(this.root, '下回合起可投骰出狱或交保释金', 0, IMG_Y + IMG_H + 36, Theme.W, 44, Theme.font.md, Theme.c.white, { bold: true });
        this.tick(Date.now());
    }

    get done(): boolean {
        return (Date.now() - this.start) * speed() >= JAIL_SHOW_MS;
    }

    tick(now: number): void {
        const t = (now - this.start) * speed(); // 快速动画：时间轴整体加速
        // 弹出：0.6 → 1.06 → 1
        const p = Math.min(1, t / POP_MS);
        const pop = p < 0.75 ? 0.6 + 0.46 * (p / 0.75) : 1.06 - 0.06 * ((p - 0.75) / 0.25);
        // 栏杆落地瞬间轻微震屏
        const hit = t - DROP_AT - DROP_MS;
        const shake = hit > 0 && hit < 220 ? Math.sin(hit / 18) * 10 * (1 - hit / 220) : 0;
        this.scene.setScale(pop, pop, 1);
        place(this.scene, IMG_X + IMG_W / 2 + shake, IMG_Y + IMG_H / 2);
        // 栏杆：从屏幕外落下（加速），落地后小幅回弹
        let y: number;
        if (t < DROP_AT) y = -BARS_H - BARS_Y;
        else if (t < DROP_AT + DROP_MS) {
            const q = (t - DROP_AT) / DROP_MS;
            y = (-BARS_H - BARS_Y) * (1 - q * q);
        } else {
            const q = Math.min(1, (t - DROP_AT - DROP_MS) / 260);
            y = -18 * Math.sin(Math.PI * q) * (1 - q);
        }
        place(this.bars, shake, BARS_Y + y);
        const fade = JAIL_SHOW_MS - t;
        setOpacity(this.root, fade < FADE_MS ? Math.round(255 * Math.max(0, fade) / FADE_MS) : 255);
    }
}
