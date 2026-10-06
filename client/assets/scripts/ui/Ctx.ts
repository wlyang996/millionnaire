/** 全局上下文：由 App 在启动时填充，页面/弹窗通过它访问 store、页面栈、弹窗栈（避免模块循环依赖）。 */
import { Node } from 'cc';
import type { Clock } from '../core/Clock';
import type { MockStore } from '../core/MockStore';
import type { PopupManager } from './PopupManager';
import type { ScreenManager } from './ScreenManager';

export interface Ctx {
    store: MockStore;
    clock: Clock;
    screens: ScreenManager;
    popups: PopupManager;
    /** 设计分辨率根节点（所有层的父节点） */
    root: Node;
}

export const ctx = {} as Ctx;
