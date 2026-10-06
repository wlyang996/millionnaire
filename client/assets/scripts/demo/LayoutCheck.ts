/**
 * 布局自检（仅演示面板的"布局自检"按钮调用）：遍历当前页面与弹窗节点树，
 * 1) 列出超出 720×1280 设计画面的节点；2) 列出与微信胶囊占位重叠的按钮类节点。
 * 跳过：滚动内容(ScrollContent)、棋盘世界(World，可缩放拖动)、名称以 ~ 开头、未激活节点、整页背景/遮罩。
 */
import { Node, UITransform } from 'cc';
import { Theme } from '../core/Theme';
import { ctx } from '../ui/Ctx';

export interface LayoutIssue { path: string; kind: 'out-of-bounds' | 'capsule-overlap'; detail: string }

const SKIP_NAMES = ['ScrollViewport', 'ScrollContent', 'HScrollViewport', 'HScrollContent', 'BoardViewport', 'World']; // 视口自身包围盒会把被裁剪的子内容算进去，故跳过
const BUTTONISH = /^(Btn:|Icon:|Seg:|Key:|Card:|Kick|Follow|Step)/;

export function checkLayout(): LayoutIssue[] {
    const issues: LayoutIssue[] = [];
    const roots: Node[] = [];
    const cur = ctx.screens.current;
    if (cur && cur.root) roots.push(cur.root);
    ctx.popups.layer.children.forEach((c) => roots.push(c));
    const base = ctx.root.getComponent(UITransform)?.getBoundingBoxToWorld();
    if (!base) return issues;
    const cap = Theme.capsule;
    // 世界坐标中的画面范围
    const left = base.x;
    const right = base.x + base.width;
    const bottom = base.y;
    const top = base.y + base.height;
    const capL = left + cap.x;
    const capR = capL + cap.w;
    const capT = top - cap.y;
    const capB = capT - cap.h;

    const walk = (n: Node, path: string): void => {
        if (!n.activeInHierarchy) return;
        if (SKIP_NAMES.indexOf(n.name) >= 0 || n.name.charAt(0) === '~') return;
        const ut = n.getComponent(UITransform);
        const here = path + '/' + n.name;
        if (ut && n.parent) {
            const r = ut.getBoundingBoxToWorld();
            const eps = 2;
            const out = r.x < left - eps || r.x + r.width > right + eps || r.y < bottom - eps || r.y + r.height > top + eps;
            if (out && !(r.width >= base.width - 1 && r.height >= base.height - 1)) {
                issues.push({
                    path: here, kind: 'out-of-bounds',
                    detail: '左' + Math.round(r.x - left) + ' 右' + Math.round(r.x + r.width - right) + ' 上' + Math.round(r.y + r.height - top) + ' 下' + Math.round(bottom - r.y),
                });
            }
            if (BUTTONISH.test(n.name)) {
                const ox = r.x < capR && r.x + r.width > capL;
                const oy = r.y < capT && r.y + r.height > capB;
                if (ox && oy) issues.push({ path: here, kind: 'capsule-overlap', detail: '与胶囊占位重叠' });
            }
        }
        for (const c of n.children) walk(c, here);
    };
    roots.forEach((r) => walk(r, ''));
    return issues;
}

export function formatIssues(list: LayoutIssue[], max = 10): string {
    if (list.length === 0) return '布局自检通过：未发现越界/压胶囊的节点';
    const lines = list.slice(0, max).map((i) => (i.kind === 'capsule-overlap' ? '[胶囊] ' : '[越界] ') + i.path.split('/').slice(-3).join('/') + ' ' + i.detail);
    return '发现 ' + list.length + ' 处：\n' + lines.join('\n') + (list.length > max ? '\n…其余见控制台' : '');
}
