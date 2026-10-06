/**
 * 应用入口（默认联机；H5 加 ?demo=1 为演示模式）：创建 Canvas/Camera，按层级装配
 *   screens(页面) < capsule(微信胶囊占位) < popups(弹窗) < demo(☰ 菜单) < toast。
 * 由 LobbyBootstrap 组件在 onLoad 里调用 start()，每帧调用 update()。
 */
import { Camera, Canvas, Color, Layers, Node, profiler, ResolutionPolicy, UITransform, Vec3, view } from 'cc';
import { MockStore, Scenario } from './core/MockStore';
import { POPUP_CATALOG } from './popups/Catalog';
import { ScreenId } from './ui/Screen';
import { Theme } from './core/Theme';
import { DemoPanel } from './demo/DemoPanel';
import { checkLayout, formatIssues } from './demo/LayoutCheck';
import { BoardScreen } from './screens/BoardScreen';
import { LobbyScreen } from './screens/LobbyScreen';
import { ProfileScreen } from './screens/ProfileScreen';
import { ResultScreen } from './screens/ResultScreen';
import { RoomScreen } from './screens/RoomScreen';
import { TeethScreen } from './screens/TeethScreen';
import { ctx } from './ui/Ctx';
import { bootArt, startBackgroundArt } from './ui/Art';
import { EditField } from './ui/EditField';
import { fillRR, gfx, mk, setText, text } from './ui/Kit';
import { PopupManager } from './ui/PopupManager';
import { ScreenManager } from './ui/ScreenManager';
import { Toast } from './ui/Toast';
import { onlineEnabled } from './net/Config';
import { emptySession, OnlineSession } from './net/OnlineSession';

export class App {
    start(host: Node): void {
        profiler.hideStats();
        view.setDesignResolutionSize(Theme.W, Theme.H, ResolutionPolicy.SHOW_ALL);
        const canvasNode = new Node('Canvas');
        canvasNode.layer = Layers.Enum.UI_2D;
        host.addChild(canvasNode);
        canvasNode.addComponent(UITransform).setContentSize(Theme.W, Theme.H);
        const canvas = canvasNode.addComponent(Canvas);
        const cameraNode = new Node('UICamera');
        canvasNode.addChild(cameraNode);
        cameraNode.setPosition(new Vec3(0, 0, 1000));
        const camera = cameraNode.addComponent(Camera);
        camera.projection = Camera.ProjectionType.ORTHO;
        camera.orthoHeight = Theme.H / 2;
        camera.near = 0.1;
        camera.far = 2000;
        camera.visibility = Layers.Enum.UI_2D;
        camera.clearFlags = Camera.ClearFlag.SOLID_COLOR;
        camera.clearColor = new Color(217, 241, 255, 255);
        canvas.cameraComponent = camera;
        canvas.alignCanvasWithScreen = true;

        // 设计画面根节点：左上角锚点，位于 Canvas 左上
        const root = mk(canvasNode, 'Root', -Theme.W / 2, -Theme.H / 2, Theme.W, Theme.H);
        const store = new MockStore();
        if (onlineEnabled()) {
            // 联机模式（默认）：数据来自服务端；H5 地址加 ?demo=1 回到演示模式
            const online = new OnlineSession(store);
            store.online = online;
            store.nowSource = () => online.now();
            store.session = emptySession();
            online.onRoute = (r) => ctx.screens.go(r);
            online.onToast = (m) => Toast.show(m);
        }
        ctx.store = store;
        ctx.clock = store.clock;
        ctx.root = root;
        ctx.screens = new ScreenManager(root);
        const demo = new DemoPanel(root); // 先建，使胶囊占位位于页面之上、弹窗之下
        ctx.popups = new PopupManager(root);
        demo.bringToFront();
        const toastLayer = mk(root, 'ToastLayer', 0, 0, Theme.W, Theme.H);
        Toast.init(toastLayer);

        const s = ctx.screens;
        s.register('profile', () => new ProfileScreen());
        s.register('lobby', () => new LobbyScreen());
        s.register('room', () => new RoomScreen());
        s.register('board', () => new BoardScreen(false));
        s.register('spectator', () => new BoardScreen(true));
        s.register('teeth', () => new TeethScreen());
        s.register('result', () => new ResultScreen());
        store.onChange(() => ctx.screens.refresh());
        // 调试句柄：浏览器控制台里可用 __mn.go('board') / __mn.popup('auction') / __mn.scenario({players:4}) 直接跳转
        (globalThis as unknown as Record<string, unknown>).__mn = {
            ctx,
            go: (id: ScreenId) => ctx.screens.go(id),
            popup: (id: string) => {
                const e = POPUP_CATALOG.find((x) => x.id === id);
                if (e) ctx.popups.openExclusive(e.make());
            },
            scenario: (p: Partial<Scenario>) => ctx.store.patchScenario(p),
            layout: () => formatIssues(checkLayout(), 40),
        };
        // 加载页：只等首屏必需的图（带进度），其余进入首屏后在后台加载
        const loading = mk(root, 'Loading', 0, 0, Theme.W, Theme.H);
        text(loading, '正在加载小镇…', 0, 520, Theme.W, 70, Theme.font.lg, Theme.c.ink, { bold: true });
        const barW = Theme.W - 220;
        const barH = 28;
        const bar = gfx(mk(loading, 'Bar', 110, 610, barW, barH));
        const pct = text(loading, '0%', 0, 652, Theme.W, 50, Theme.font.md, Theme.c.inkSoft);
        const progress = (done: number, total: number) => {
            const k = total > 0 ? done / total : 1;
            bar.clear();
            fillRR(bar, 0, 0, barW, barH, barH / 2, '#FFFFFFB0');
            if (k > 0) fillRR(bar, 0, 0, Math.max(barH, barW * k), barH, barH / 2, Theme.c.blue);
            setText(pct, Math.round(k * 100) + '%');
        };
        void bootArt(progress).then((missing) => {
            loading.destroy();
            s.go('profile');
            if (missing.length) Toast.show('部分美术加载失败，请刷新重试');
            // 页面用到、但当时还没加载的图到达后重绘当前页；正在输入时等输入结束再重绘
            let waiting = false;
            const redraw = () => {
                if (EditField.editing === 0) {
                    ctx.screens.refresh();
                    ctx.popups.refreshArt();
                    return;
                }
                if (waiting) return; // 输入期间到达的多批图合并成输入结束后的一次重绘
                waiting = true;
                setTimeout(() => {
                    waiting = false;
                    redraw();
                }, 500);
            };
            startBackgroundArt(redraw);
        });
    }

    update(dt: number): void {
        if (!ctx.screens) return;
        ctx.screens.update(dt);
        ctx.popups.update();
        Toast.update();
    }
}
