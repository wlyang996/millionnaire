/**
 * 顶部玩家条（设计稿 01）：最多 8 人，2 行 × 4 列，每格 152×68；头像、昵称、金币现金；当前玩家黄框浅黄底；"我"蓝色角标。
 * 不显示现金变化的加减额（用户要求：不要扣钱 / 加钱提示）。
 */
import { Node } from 'cc';
import { ConnState, ControlMode, PlayerView } from '../../core/Models';
import { Theme } from '../../core/Theme';
import { drawCoin } from '../../ui/Icons';
import { fillCircle, fillRR, gfx, mk, onTap, strokeRR, text } from '../../ui/Kit';
import { avatar } from '../../ui/Widgets';

export interface StatusBadge { text: string; bg: string; fg: string }
export interface CashChange { amount: number; until: number }
export interface CashChangeNode { node: Node; badge: Node | null; until: number }

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
        const coin = gfx(mk(cell, 'Coin', 66, 36, 22, 22));
        drawCoin(coin, 11, 11, 10);
        text(cell, String(p.cash), 92, 33, cw - 96, 28, 22, ink, { bold: true, align: 'l' });
        const b = statusBadge(p) ?? (p.playerId === drawingId ? { text: '正在抽取事件卡', bg: '#FFF1C9', fg: '#7A5A00' } : null);
        if (b) {
            const bn = mk(cell, 'Badge', 64, ch - 17, cw - 68, 16);
            fillRR(gfx(bn), 0, 0, cw - 68, 16, 8, b.bg);
            text(bn, b.text, 2, 0, cw - 72, 16, 12, b.fg, { bold: true });
        }
        void changes;
        void changeNodes;
    });
    return bar;
}
