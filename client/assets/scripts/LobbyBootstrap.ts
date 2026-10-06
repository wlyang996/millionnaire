import { _decorator, Component } from 'cc';
import { App } from './App';
const { ccclass } = _decorator;

/**
 * 场景入口组件（Lobby.scene 里挂的就是它，场景文件不变）。
 * 现在只负责启动 App：整套界面（登录资料/大厅/房间/棋盘/虎口拔牙/结算 + 弹窗 + 演示菜单）由 TS 在运行时代码创建。
 */
@ccclass('LobbyBootstrap')
export class LobbyBootstrap extends Component {
    private readonly app = new App();

    onLoad(): void {
        this.app.start(this.node);
    }

    update(dt: number): void {
        this.app.update(dt);
    }
}
