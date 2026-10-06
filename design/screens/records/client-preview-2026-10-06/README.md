# 客户端实际预览 · 2026-10-06

本目录为 Cocos Creator 3.8.8 web-mobile 构建的浏览器截图，测试视口为 390 × 844。设计原图和候选图未覆盖。

- `profile.jpg`、`lobby.jpg`、`room.jpg`：资料、大厅、八人房间。
- `board.jpg`、`board-30.jpg`、`other-turn.jpg`：五十格、三十格、他人回合。
- `buy.jpg`、`event-back.jpg`、`event-result.jpg`：购买及本人点击事件卡背、翻牌结果。
- `teeth.jpg`、`spectator.jpg`、`result.jpg`：虎口拔牙、破产观战、八人结算。

已验证素材显示、棋盘格点击、手牌横滑、投骰移动、抽卡、大厅进入房间，以及三十格和五十格布局检查。TypeScript 检查、192 项原逻辑自检及官方 web-mobile 构建通过。

仍为本地 MockStore 演示；未执行微信构建或真机测试。布局并非设计图的逐像素复刻，骰子及人物仍采用位图动画。素材导入对照见同级 `client-art-import-2026-10-06.json`。
