/** 顶部玩家条：最多 8 人（2 行 × 4 列），现金、当前玩家高亮、连接/托管状态标记。 */
import { Node } from 'cc';
import { ConnState, ControlMode, PlayerView } from '../../core/Models';
import { Theme } from '../../core/Theme';
import { drawCoin } from '../../ui/Icons';
import { fillCircle, fillRR, gfx, mk, strokeRR, text } from '../../ui/Kit';
import { avatar } from '../../ui/Widgets';

export interface StatusBadge { text: string; bg: string; fg: string }

/** 连接/控制状态标记：已掉线·自动投骰 / 疑似断线 / 托管中 / 暂离。破产单独标记。 */
export function statusBadge(p: PlayerView): StatusBadge | null {
    if (p.life === 'BANKRUPT') return { text: '已破产', bg: Theme.c.redSoft, fg: Theme.c.redDark };
    if (p.life === 'SURRENDERED') return { text: '已认输', bg: '#E9EDF1', fg: Theme.c.inkSoft };
    return connBadge(p.conn, p.control);
}

export function connBadge(conn: ConnState, control: ControlMode): StatusBadge | null {
    if (conn === 'OFFLINE') return { text: '已掉线·自动投骰', bg: '#E1E5EA', fg: Theme.c.inkSoft };
    if (conn === 'SUSPECT') return { text: '疑似断线', bg: '#FFE9A8', fg: '#7A5A00' };
    if (control === 'HOSTED') return { text: '托管中', bg: Theme.c.blue, fg: Theme.c.white };
    if (control === 'AWAY') return { text: '暂离', bg: '#E3D6FF', fg: '#5B3BA6' };
    return null;
}

export function drawPlayerBar(parent: Node, x: number, y: number, players: PlayerView[], currentId: string, myId: string, drawingId: string | null = null): Node {
    const cw = 168;
    const ch = 76;
    const gap = 6;
    const bar = mk(parent, 'PlayerBar', x, y, 4 * cw + 3 * gap, 2 * ch + gap);
    players.slice(0, 8).forEach((p, i) => {
        const cx = (i % 4) * (cw + gap);
        const cy = Math.floor(i / 4) * (ch + gap);
        const cell = mk(bar, 'P:' + p.playerId, cx, cy, cw, ch);
        const g = gfx(cell);
        const cur = p.playerId === currentId && p.life === 'ALIVE';
        const dead = p.life !== 'ALIVE';
        fillRR(g, 0, 3, cw, ch, 16, Theme.c.shadow);
        fillRR(g, 0, 0, cw, ch, 16, dead ? '#E3E7EB' : cur ? '#FFF3C4' : '#FFFFFFE6');
        if (cur) strokeRR(g, 0, 0, cw, ch, 16, Theme.c.yellow, 4);
        avatar(cell, 6, 8, 44, p.avatar, p.nickname, { dim: dead });
        if (p.playerId === myId) {
            const badge = mk(cell, 'MeBadge', 2, 2, 24, 24);
            fillCircle(gfx(badge), 12, 12, 12, Theme.c.blue);
            text(badge, '我', 0, 0, 24, 24, 14, Theme.c.white, { bold: true });
        }
        text(cell, p.nickname, 54, 4, cw - 58, 26, 20, dead ? Theme.c.inkFaint : Theme.c.ink, { bold: true, align: 'l' });
        const coin = gfx(mk(cell, 'Coin', 54, 32, 22, 22));
        drawCoin(coin, 11, 11, 9);
        text(cell, String(p.cash), 80, 30, cw - 84, 26, 22, dead ? Theme.c.inkFaint : Theme.c.ink, { bold: true, align: 'l' });
        const b = statusBadge(p) ?? (p.playerId === drawingId ? { text: '正在抽取事件卡', bg: '#FFF1C9', fg: '#7A5A00' } : null);
        if (b) {
            const bn = mk(cell, 'Badge', 54, 54, cw - 60, 20);
            fillRR(gfx(bn), 0, 0, cw - 60, 20, 10, b.bg);
            text(bn, b.text, 2, 0, cw - 64, 20, 14, b.fg, { bold: true });
        }
    });
    return bar;
}
