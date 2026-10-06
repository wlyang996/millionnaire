/**
 * 输入框封装（cc.EditBox，代码创建）。EditBox 内部按居中锚点布局文字，所以这里的节点用默认居中锚点，
 * 与 Kit.mk 的左上锚点约定不同；外观（底板）画在单独的左上锚点节点上。
 * 未在真机/编辑器预览验证输入法与对齐；页面同时提供"随机昵称/测试昵称"按钮，不依赖键盘也可演示。
 */
import { EditBox, Label, Layers, Node, UITransform, Vec3 } from 'cc';
import { Theme } from '../core/Theme';
import { col, fillRR, gfx, mk, strokeRR } from './Kit';

export class EditField {
    readonly node: Node;
    private readonly box: EditBox;
    private readonly bg: Node;
    private readonly w: number;
    private readonly h: number;

    constructor(
        parent: Node, x: number, y: number, w: number, h: number, placeholder: string,
        maxLength: number, onChange: (s: string) => void,
    ) {
        this.w = w;
        this.h = h;
        this.bg = mk(parent, 'EditBg', x, y, w, h);
        this.paint(false);
        const n = new Node('EditBox');
        n.layer = Layers.Enum.UI_2D;
        const ut = n.addComponent(UITransform);
        ut.setContentSize(w, h);
        n.setPosition(new Vec3(x + w / 2, -(y + h / 2), 0));
        parent.addChild(n);
        const box = n.addComponent(EditBox);
        // addComponent 时引擎已自动创建 TEXT_LABEL / PLACEHOLDER_LABEL，直接设置样式即可（自建会留下一个多余的 "label"）
        this.styleLabel(box.textLabel, Theme.font.md, Theme.c.ink, w, h);
        this.styleLabel(box.placeholderLabel, Theme.font.md, Theme.c.inkFaint, w, h);
        box.placeholder = placeholder;
        box.maxLength = maxLength;
        box.inputMode = EditBox.InputMode.SINGLE_LINE;
        n.on('text-changed', (eb: EditBox) => onChange(eb.string));
        n.on('editing-did-began', () => this.paint(true));
        n.on('editing-did-ended', (eb: EditBox) => {
            this.paint(false);
            onChange(eb.string);
        });
        this.box = box;
        this.node = n;
    }

    /** 引擎按"左上锚点"摆放这两个 Label（位置 = 左上角），但创建时锚点是居中，需改成 (0,1) 并固定框大小。 */
    private styleLabel(l: Label | null, size: number, color: string, w: number, h: number): void {
        if (!l) return;
        const ut = l.node.getComponent(UITransform);
        if (ut) {
            ut.setAnchorPoint(0, 1);
            ut.setContentSize(w - 4, h);
        }
        l.overflow = Label.Overflow.CLAMP;
        l.fontSize = size;
        l.lineHeight = size + 8;
        l.color = col(color);
        l.horizontalAlign = Label.HorizontalAlign.LEFT;
        l.verticalAlign = Label.VerticalAlign.CENTER;
    }

    private paint(focus: boolean): void {
        const g = gfx(this.bg);
        g.clear();
        fillRR(g, 0, 0, this.w, this.h, 18, Theme.c.white);
        strokeRR(g, 0, 0, this.w, this.h, 18, focus ? Theme.c.blue : Theme.c.ivoryLine, focus ? 4 : 3);
    }

    get value(): string {
        return this.box.string;
    }

    set value(s: string) {
        this.box.string = s;
    }
}
