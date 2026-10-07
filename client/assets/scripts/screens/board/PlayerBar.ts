/**
 * 顶部玩家条（设计稿 01）：最多 8 人，2 行 × 4 列，每格 152×68；头像、昵称、金币现金；当前玩家黄框浅黄底；"我"蓝色角标。
 * 现金变化时在现金旁浮出"+500"（绿）/"-300"（红）的小标签，向上飘起并渐隐（用户 2026-10-07 要求的动态效果，
 * 取代早先的文字提示）；动画由 BoardScreen.tickCashChanges 按时间驱动，重建时按剩余时间接着播。
 */
import { Node } from 'cc';
import { ConnState, ControlMode, PlayerView } from '../../core/Models';
import { Theme } from '../../core/Theme';
import { drawCoin } from '../../ui/Icons';
import { fillCircle, fillRR, gfx, mk, onTap, place, setOpacity, strokeRR, text } from '../../ui/Kit';
import { textWidth } from '../../core/Theme';
import { avatar } from '../../ui/Widgets';

export interface StatusBadge { text: string; bg: string; fg: string }
export interface CashChange { amount: number; until: number }
export interface CashChangeNode { node: Node; badge: Node | null; until: number; x: number; y: number }

/** 现金变化浮动标签的总时长与上飘距离。 */
export const CASH_DELTA_MS = 2600;
const CASH_DELTA_RISE = 26;

/** 在 (x, y) 处放一个"+N / -N"标签（左边缘为 x，标签竖直中心为 y），登记到 nodes 由每帧动画驱动。 */
export function cashDelta(parent: Node, x: number, y: number, change: CashChange, nodes: CashChangeNode[], size = 20): void {
    if (change.amount === 0 || change.until <= Date.now()) return;
    const label = (change.amount > 0 ? '+' : '-') + Math.abs(change.amount);
    const h = size + 8;
    const w = textWidth(label, size) + 16;
    const n = mk(parent, 'CashDelta', x, y - h / 2, w, h);
    fillRR(gfx(n), 0, 0, w, h, h / 2, change.amount > 0 ? '#1E9E43' : '#E5303A');
    text(n, label, 0, 0, w, h, size, '#FFFFFF', { bold: true });
    const entry = { node: n, badge: null, until: change.until, x, y: y - h / 2 };
    nodes.push(entry);
    tickCashDelta(entry, Date.now());
}

/** 每帧：前 85% 时间上飘并保持不透明，最后渐隐；到时隐藏。 */
export function tickCashDelta(c: CashChangeNode, now: number): void {
    if (!c.node.isValid) return;
    const remaining = c.until - now;
    c.node.active = remaining > 0;
    if (c.badge?.isValid) c.badge.active = remaining <= 0;
    if (remaining <= 0) return;
    const k = Math.min(1, Math.max(0, 1 - remaining / CASH_DELTA_MS));
    const ease = 1 - (1 - k) * (1 - k);
    place(c.node, c.x, c.y - CASH_DELTA_RISE * ease);
    setOpacity(c.node, k < 0.85 ? 255 : Math.round(255 * (1 - (k - 0.85) / 0.15)));
}

/** 连接/控制状态标记：已掉线·自动投骰 / 疑似断线 / 托管中 / 挂机（暂离）。破产单独标记。 */
export function statusBadge(p: PlayerView): StatusBadge | null {
    if (p.life === 'BANKRUPT') return { text: '已破产', bg: Theme.c.redSoft, fg: Theme.c.redDark };
    if (p.life === 'SURRENDERED') return { text: '已认输', bg: '#E9EDF1', fg: Theme.c.inkSoft };
    return connBadge(p.conn, p.control);
}

export function connBadge(conn: ConnState, control: ControlMode): StatusBadge | null {
    if (conn === 'OFFLINE') return { text: '已掉线·自动投骰', bg: '#E1E5EA', fg: Theme.c.inkSoft };
    if (conn === 'SUSPECT') return { text: '疑似断线', bg: '#FFE9A8', fg: '#7A5A00' };
    if (control === 'HOSTED') return { text: '托管中', bg: Theme.c.blue, fg: Theme.c.white };
    if (control === 'AWAY') return { text: '挂机', bg: '#E3D6FF', fg: '#5B3BA6' };
    return null;
}

export function drawPlayerBar(parent: Node, x: number, y: number, players: PlayerView[], currentId: string, myId: string, drawingId: string | null = null,
    onPlayerTap?: (id: string) => void, changes: Map<string, CashChange> = new Map(), changeNodes: CashChangeNode[] = []): Node {
    const cw = 152;
    const ch = 68;
    const gapX = 9;
    const gapY = 8;
    const bar = mk(parent, 'PlayerBar', x, y, 4 * cw + 3 * gapX, 2 * ch + gapY);
    players.slice(0, 8).forEach((p, i) => {
        const cx = (i % 4) * (cw + gapX);
        const cy = Math.floor(i / 4) * (ch + gapY);
        const cell = mk(bar, 'P:' + p.playerId, cx, cy, cw, ch);
        if (onPlayerTap) onTap(cell, () => onPlayerTap(p.playerId), false);
        const g = gfx(cell);
        const cur = p.playerId === currentId && p.life === 'ALIVE';
        const dead = p.life !== 'ALIVE';
        fillRR(g, 0, 3, cw, ch, 14, Theme.c.shadow);
        fillRR(g, 0, 0, cw, ch, 14, dead ? '#E3E7EB' : cur ? '#FFF6D6' : '#FFFFFFEE');
        if (cur) strokeRR(g, 1, 1, cw - 2, ch - 2, 14, Theme.c.yellow, 3);
        avatar(cell, 5, 6, 56, p.avatar, p.nickname, { dim: dead });
        if (p.playerId === myId) {
            const badge = mk(cell, 'MeBadge', 2, 2, 24, 24);
            fillCircle(gfx(badge), 12, 12, 12, Theme.c.blue);
            text(badge, '我', 0, 0, 24, 24, 14, Theme.c.white, { bold: true });
        }
        const ink = dead ? Theme.c.inkFaint : Theme.c.navy;
        text(cell, p.nickname, 66, 6, cw - 70, 26, 20, ink, { bold: true, align: 'l' });
        const b = statusBadge(p) ?? (p.playerId === drawingId ? { text: '抽卡中', bg: '#FFF1C9', fg: '#7A5A00' } : null);
        if (b) {
            // 设计稿 06（连接状态）：状态胶囊占据现金那一行；"已掉线·自动投骰"分两行
            const lines = b.text.split('·');
            const two = lines.length > 1;
            const bh = two ? 34 : 26;
            const bw = cw - 72;
            const bn = mk(cell, 'Badge', 66, two ? 31 : 35, bw, bh);
            fillRR(gfx(bn), 0, 0, bw, bh, 9, b.bg);
            text(bn, two ? lines.join('\n') : b.text, 2, 0, bw - 4, bh, two ? 13 : 16, b.fg, { bold: true, lineHeight: 15 });
        } else {
            const coin = gfx(mk(cell, 'Coin', 66, 36, 22, 22));
            drawCoin(coin, 11, 11, 10);
            text(cell, String(p.cash), 92, 33, cw - 96, 28, 22, ink, { bold: true, align: 'l' });
        }
        const change = changes.get(p.playerId);
        // 浮在现金正上方（昵称那一行），向上飘出卡片
        if (change) cashDelta(cell, 88, 24, change, changeNodes, 18);
    });
    return bar;
}
