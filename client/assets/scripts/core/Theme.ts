/**
 * 视觉令牌：颜色 / 字号 / 圆角 / 间距集中在此，换正式美术时只改这里。
 * 颜色一律用 '#RRGGBB' 或 '#RRGGBBAA' 字符串，由 ui/Kit.ts 的 col() 转成 cc.Color（本文件不依赖 cc，便于无头测试）。
 */
export const Theme = {
    /** 设计分辨率（竖屏） */
    W: 720,
    H: 1280,
    /** 顶部安全区：微信胶囊占据右上角，内容与可点元素需避开 */
    safeTop: 100,
    /** 胶囊占位（x,y,w,h），仅供布局自检/演示绘制 */
    capsule: { x: 524, y: 22, w: 184, h: 64 },
    /** 底部安全区（Home 条） */
    safeBottom: 24,

    c: {
        skyTop: '#8FD3FF',
        skyBottom: '#D9F1FF',
        grass: '#9BDB7A',
        grassDark: '#6FBF5B',
        ivory: '#FFF8E6',
        ivoryDark: '#F3E7C4',
        ivoryLine: '#E6D5A8',
        ink: '#2D3B4A',
        inkSoft: '#6B7A8A',
        inkFaint: '#A9B4BF',
        white: '#FFFFFF',
        yellow: '#FFC83D',
        yellowDark: '#E39B10',
        yellowText: '#5A3A00',
        blue: '#4DA3F0',
        blueDark: '#2B79C4',
        blueSoft: '#DDEFFD',
        green: '#4CC26B',
        greenDark: '#2F9A4C',
        greenSoft: '#DDF6E3',
        red: '#EE5A5A',
        redDark: '#C03A3A',
        redSoft: '#FDE0E0',
        orange: '#FF9A3D',
        purple: '#A66CFF',
        gray: '#C9D0D8',
        grayDark: '#9AA5B1',
        mask: '#000000A6',
        maskLight: '#00000066',
        shadow: '#00000026',
        tierLow: '#7ED56F',
        tierMid: '#4DA3F0',
        tierHigh: '#A66CFF',
        station: '#5B6B7A',
        tileBase: '#FFFDF5',
        /** 本局剩余时间：正常深蓝 / 警示红（设计图 09-ui-states） */
        clockNormal: '#1F3F7A',
        clockRed: '#E5303A',
        /** 弹窗（设计稿 04/05/17）：深蓝标题与数字、暖白面板、米色数值底、浅灰说明底、米色胶囊、浅蓝次要按钮 */
        navy: '#1E2A6B',
        panelFill: '#FFFBF3',
        panelLine: '#EFE2C6',
        boxBeige: '#FBF0DC',
        boxGray: '#F1F2F5',
        pill: '#F6E8CB',
        softBlue: '#DDEBFA',
        softBlueHi: '#EBF4FE',
        softBlueEdge: '#A9C6EA',
        noteGray: '#5B6B8C',
        payRed: '#E5303A',
        gainGreen: '#1E9E43',
    },

    /** 8 种头像底色（顺序固定，按头像编号取） */
    avatarColors: ['#4DA3F0', '#FF8FB1', '#4CC26B', '#FFA94D', '#A66CFF', '#26C6B0', '#F2C037', '#8D7B6A'],

    font: { xs: 20, sm: 24, md: 28, lg: 34, xl: 44, xxl: 64 },
    radius: { sm: 12, md: 20, lg: 28, pill: 999 },
    gap: { xs: 8, sm: 16, md: 24, lg: 32 },
    /** 页面左右留白 */
    pad: 24,
    /** 动画时长（毫秒）：联调时按真实动画调整；点数/结果永远由对局结果决定，动画只是表现 */
    anim: {
        diceMs: 1200, hopMs: 550, blinkMs: 1000, blinkLow: 0.35, pulseMs: 700,
        diceReadyMs: 1400, eventResultHoldMs: 3000,
        /** 事件卡：翻牌时长 / 点击等待超时系统代抽（15s 为沿用口径，待规则确认）/ 呼吸提示周期 / 他人抽卡结果展示时长 */
        eventFlipMs: 700, eventAutoMs: 15000, eventBreathMs: 1400, eventOtherHoldMs: 1500,
    },
    /** 棋盘页布局常量（SelfCheck 校验不超出 720 宽） */
    board: { playerCols: 4, handVisible: 5, handItemW: 116, handGap: 6, handViewW: 604 },
    /** 弹窗倒计时环在面板内的固定位置（相对面板右上角的内缩量） */
    popupRing: { size: 124, inset: 16 },
};

/** 大致估算文字宽度（CJK 按 1 个字号宽，ASCII/数字按 0.56），用于 Chip 等自适应宽度。 */
export function textWidth(str: string, size: number): number {
    let w = 0;
    for (const ch of str) {
        w += ch.charCodeAt(0) > 0x2e80 ? size : size * 0.56;
    }
    return Math.ceil(w);
}
