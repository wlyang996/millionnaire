/** Approved asset/tile design: a full-page illustration with live labels and hit areas. */
import { BlockInputEvents, Node } from 'cc';
import { Theme } from '../core/Theme';
import { art } from '../ui/Art';
import { col, fillRR, gfx, mk, onTap, paintPanel, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';

export abstract class InformationPage extends Popup {
    get coversScreen(): boolean { return true; }
    protected get headingY(): number { return Theme.safeTop + 12; }
    protected get headingSize(): number { return 44; }
    protected get headingOutline(): number { return 3; }
    protected constructor(id: string, title: string) { super(id, title, Theme.W, Theme.H); }

    mount(layer: Node): void {
        this.root = mk(layer, 'Popup:' + this.popupId, 0, 0, Theme.W, Theme.H);
        this.root.addComponent(BlockInputEvents);
        fillRR(gfx(this.root), 0, 0, Theme.W, Theme.H, 0, Theme.c.skyBottom);
        art(this.root, 'information_background', 0, 0, Theme.W, Theme.H, 'stretch');
        this.panel = this.root;
        this.body = mk(this.root, 'Body', 0, 0, Theme.W, Theme.H);
        this.buildBody(this.body, Theme.W, Theme.H);
        const back = mk(this.root, 'Back', 32, Theme.safeTop + 12, 64, 64);
        fillRR(gfx(back), 0, 0, 64, 64, 32, Theme.c.ivory);
        art(back, 'icon_back', 18, 18, 28, 28);
        onTap(back, () => this.close());
        const heading = text(this.root, this.title, 130, this.headingY, 430, 80, this.headingSize, '#101A50', { bold: true });
        heading.enableOutline = this.headingOutline > 0;
        heading.outlineColor = col('#FFFFFF');
        heading.outlineWidth = this.headingOutline;
    }
}

/** Flat rounded surfaces explicitly defined by screens18/21; no replacement illustrations. */
export function informationCard(parent: Node, x: number, y: number, w: number, h: number,
    fill = '#FFFEF8', radius = 30, shadow = true): Node {
    const n = mk(parent, 'InformationCard', x, y, w, h);
    paintPanel(n, w, h, { fill, r: radius, stroke: '#F0E4CB', strokeW: 1,
        shadow: shadow ? 3 : 0, shadowColor: '#F5DC9E' });
    return n;
}

export function informationPanel(parent: Node, key: string, x: number, y: number, w: number, h: number): Node {
    const n = mk(parent, key, x, y, w, h);
    if (!art(n, key, 0, 0, w, h, 'panel')) fillRR(gfx(n), 0, 0, w, h, 24, Theme.c.ivory);
    return n;
}

export function informationClose(parent: Node, x: number, y: number, w: number, close: () => void): void {
    const n = mk(parent, 'Close', x, y, w, 86);
    art(n, 'button_flat_gray', 0, 0, w, 86, 'capsule');
    text(n, '关闭', 12, 0, w - 24, 80, 40, '#101A50', { bold: true });
    onTap(n, close);
}

export function shopKey(type: string, tier?: string): string {
    return type === 'STATION' ? 'shop_station' : tier === 'HIGH' ? 'shop_high' : tier === 'MID' ? 'shop_mid' : 'shop_low';
}
