# 实际浏览器验证

2026-10-06，Cocos web-mobile，本地 MockStore，390×844 手机尺寸。此目录是客户端截图，不是设计原稿，也不代表所有页面已经严格还原。

- `profile-keke.jpg`、`room-keke-preserved.jpg`：选择可可，登录保存、创建房间后保持；对局玩家头像也正确。
- `other-player-assets.jpg`：点击其他玩家，显示其资金和公开房产数量，无蓝色详情箭头。
- `property-current-level.jpg`：真实等级在租金表中标记“当前”，价格／升级／租金分区。
- `station-same-layout.jpg`：同一布局，车站不可升级，抵押时不收租。
- `cash-plus-300.jpg`／`cash-minus-400.jpg`：开发演示菜单触发本地资金变化；对应玩家格绿色+300／红色-400。游戏正常资金更新复用同一显示逻辑。
- `other-event-no-top-toast.jpg`：他人抽卡结果同步可见；顶部没有事件黑色Toast；罚款减额显示在他人玩家格。
- `card-detail-no-map.jpg`：整页道具详情不透出底层棋盘。16稿大卡、按钮及规则图标仍缺最终独立素材，现有图片仅用于结构预览。
- `dice-roll-mid.jpg`／`dice-roll-later.jpg`／`pawn-move-mid.jpg`：动画过程帧，连续移动及旋转；并非完整3D或多姿态人物动画。
- `chat-approved-bubbles.jpg`：头像、昵称、彩色气泡、右上角关闭及八句快捷短语。
- `lobby-layout.jpg`：大厅结构与已有场景素材；08四人组合插画及最终品牌图缺少。

TypeScript通过，196项逻辑自检无失败，官方web-mobile构建通过。头像新增4项回归检查。未连接正式服务端，未验证微信真机。更完整的未对齐清单见上一级`ui-corrections-2026-10-06.md`。
