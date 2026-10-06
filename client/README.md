# Cocos 客户端（演示模式，不连后端）

工程路径：F:\work\millionnaire\client。编辑器：Cocos Creator 3.8.8，场景 `assets/scenes/Lobby.scene`（未改动），
入口脚本 `assets/scripts/LobbyBootstrap.ts` → `App.ts`。**整套界面由 TS 在运行时创建**，使用 Sprite 位图美术、Graphics 面板和 Label 动态文字。2026-10-06 已从 `design/screens/` 导入 86 个原始 PNG 到 `assets/resources/art/`，首次页面显示前预加载；昵称、价格、倒计时和点击区域仍由代码创建。
当前是"可点、可切换、可评审"的界面原型：用 `MockStore` 的演示数据驱动，没有调用后端，不产生真实房间；没有接入微信身份、分享、实时语音。

## 在编辑器里预览
1. 使用 Cocos 账号登录 Dashboard；如编辑器列表为空，添加本地版本 F:\tools\Cocos\Creator\3.8.8。
2. 导入项目 F:\work\millionnaire\client，用 3.8.8 打开，等待首次资源导入和脚本编译（会为新脚本生成 .meta 文件，属正常，需要一并提交）。
3. 双击 `assets/scenes/Lobby.scene`，点击顶部预览按钮（浏览器预览即可）。
4. 预览窗口选竖屏（720×1280，适配方式 SHOW_ALL）。桌面浏览器里鼠标点击/拖动/滚轮可用；手机模拟模式下用触摸。

## 演示菜单（评审用）
- 每个页面左上角常驻深色圆形"☰"按钮（右上角浅色胶囊是**微信胶囊占位**，真机由微信绘制，演示菜单里可隐藏）。
- 页面：一键跳转 登录资料 / 大厅 / 好友房间 / 对局棋盘 / 虎口拔牙 / 结算 / 破产观战。
- 弹窗：买地、买地(拍卖地)、买地(车站)、升级、租金响应、交易确认、拍卖、欠款首段、欠款第二段、资产总览、弃牌、重连同步遮罩、二次确认(认输)、房号加入、我的战绩、聊天、Toast。
- 演示场景：人数 2～8、30/50 格（30 格最多 4 人，选 >4 人时自动改回 50 格）、当前回合 我/他人、我的身份(正常/破产观战)、连接状态(全部正常/混合[疑似断线·已掉线自动投骰·托管中·暂离]/我疑似断线)、是否房主、手牌张数(0/3/6/7)、对手回合自动演进开关。
- 倒计时：全部由"截止时间"驱动（`core/Clock.ts`）；"暂停倒计时"冻结虚拟时间（所有弹窗/回合钟一起停），"重置当前弹窗"让最上层弹窗重新计时。
- 工具："布局自检"列出超出 720×1280 或压到胶囊占位的节点（同时 console.warn）；"逻辑自检"运行 `core/SelfCheck.ts`（规则数值/棋盘模板/排名/昵称/倒计时/场景一致性断言）。
- 浏览器控制台调试句柄：`__mn.go('board')`、`__mn.popup('auction')`、`__mn.scenario({players:4})`、`__mn.layout()`。

可以真的玩一小段：棋盘页点"投骰子"→骰子动画→棋子前进→按落点弹出 买地/升级/租金响应(有免租卡时)/事件现金/虎口拔牙，弹窗都关闭后自动轮到下一位；"演示：轮到我"可把回合拉回自己。大厅"创建房间/房号加入(000000=不存在，888888=已满)"→房间页（点别人的"准备"标签可切换其状态以演示开局）。

## 目录与架构（assets/scripts，62 个 .ts）
```
App.ts / LobbyBootstrap.ts     入口：Canvas+Camera，层级 screens < 胶囊占位 < popups < 演示菜单 < toast；LobbyBootstrap 只是挂在场景上的壳
core/   纯 TS，不依赖 cc（可用 node 直接断言）
  Theme.ts        颜色/字号/圆角/间距令牌 + 弹窗倒计时固定位置
  Models.ts       数据模型接口（PlayerView/BoardTile/PropertyState/Card/RoomSettings/GameView/SessionView…），注释标注 [公开]/[私有]/[待服务端]
  Rules.ts        规则常量与纯函数：租金/升级/标准价值/应急抵押比例/拍卖参数/交易范围/欠款差额/净资产/并列排名(1,1,3)/昵称检查
  Clock.ts        可暂停的虚拟时钟 + 截止时间驱动的 Countdown
  BoardLayout.ts  30/50 格模板（地产三档、拍卖地产标记）与环形网格坐标
  MockStore.ts    演示数据仓库与可切换场景；接后端时用服务端 SessionView 替换 session 后 emit()
  SelfCheck.ts    无头自检
ui/     Art/ArtCatalog(资源预加载、头像/棋子/卡面映射) Kit(节点/绘制原语) Buttons(Primary/Secondary/Ghost/Danger/IconButton) Widgets(RoundedPanel/Avatar/Chip/CountdownRing/CountdownLabel/Segmented)
        ScrollList(Mask 裁剪+拖动+滚轮) Keypad EditField(EditBox 封装) Icons(14 种道具与常用图标) Toast
        Screen/ScreenManager(页面栈+淡入) Popup/PopupManager(弹窗栈+遮罩+倒计时环) Backdrop Ctx
screens/ ProfileScreen LobbyScreen RoomScreen BoardScreen(含观战) TeethScreen ResultScreen；board/ 下 BoardView(缩放拖动跟随) PlayerBar HandBar
popups/  BuyPopup UpgradePopup RentPopup TradePopup AuctionPopup DebtPopup DebtSecondPopup AssetsPopup DiscardPopup ResyncPopup
         ConfirmPopup(含认输) JoinRoomPopup HistoryPopup ChatPopup；Catalog.ts 供演示面板列出/创建；Common.ts 共用部件
demo/    DemoPanel LayoutCheck
```
约定：所有节点锚点为**左上角**，布局坐标 y 向下、原点为父节点左上（`ui/Kit.mk(parent,name,x,y,w,h)` 的 (x,y) 就是设计稿像素）；Graphics/Label/Mask 不放同一节点。
`ctx`（ui/Ctx.ts）持有 store/clock/screens/popups，避免模块循环依赖。

## 验证方法（在仓库外的临时目录运行，不改工程文件）
1. TypeScript 类型检查（安装版 3.8.8 的真实 cc.d.ts + 自带 TypeScript 5.8.2）：
   tsconfig 内容：`strict`、`experimentalDecorators`、`useDefineForClassFields:false`、`files:[client/temp/validation/cc.d.ts]`、`include:[client/assets/scripts/**/*.ts]`；
   运行 `node F:\tools\Cocos\Creator\3.8.8\resources\app.asar.unpacked\node_modules\typescript\bin\tsc -p <该 tsconfig>`。
2. 命令行构建：`CocosCreator.exe --project F:\work\millionnaire\client --build "platform=web-mobile;debug=true;buildPath=build;startScene=20e5cd30-5cba-4654-834a-53017b73ac0a;scenes=[{\"uuid\":\"20e5cd30-5cba-4654-834a-53017b73ac0a\"}]"`，退出码 36 为成功；`client/build` 不要提交。
3. 无头逻辑自检：`tsc --target ES2020 --module commonjs --strict --outDir <out> assets/scripts/core/SelfCheck.ts`，然后 `node -e "require('<out>/SelfCheck.js').runSelfCheck()"`。

## v6 棋盘更新（依据 design/screens/01-board.png、02-motion-storyboard.png、09-ui-states.png）
- 地名：`core/BoardNames.ts`（取自 map-ui-definition.json，三字地名）；格子/详情/资产/交易/拍卖统一取 `tile.name`；点击棋盘格子打开详情。SelfCheck 校验 30/50 格逐格 col/row/类型/档位/名称、地名无重复、低中高 6/6/4 与 10/10/8。
- 我的棋子：更大、"我·昵称"气泡、发光底座；取消放大/缩小/定位按钮（双指/滚轮缩放与拖动保留，每次换人恢复跟随）。
- 动画（时间戳驱动，常量在 `Theme.anim`）：骰子翻滚约 1.2s（`ui/DiceView.ts`）；逐格跳跃每步约 250ms，含蓄力/腾空/落地尘土/到达"我"光圈（`BoardView.hopTo`）。演示菜单有"骰子翻滚/逐格跳"触发。
- 本局剩余时间警示：`core/MatchClock.ts` 纯函数（≥600 正常深蓝；180–599 红色常亮；1–179 红色闪烁 1s 周期 100%↔35%；0 红色常亮），只作用于顶部时钟与图标；演示菜单可固定 12:00/09:59/03:00/02:59/00:30/00:00。
- 道具栏：单排、默认 5 项、横向滑动（`ui/HScroll.ts`，位移>10px 判滑动）、右列 6/6 + 箭头 + 滑动指示；同类合并带角标；演示手牌=免租/建造/拍卖/定点移动/路障/出狱。
- 底部资产条改深蓝（设计图 01）；中央回合提示为"轮到你了/剩余 15秒"药丸 + 可点击骰子；按最新用户修正取消投骰按钮。

## 事件卡牌堆（design/screens/10、11）
- 默认：棋盘内圈左上角三张扇形问号卡背（`screens/board/EventDeck.ts`）；走到事件格：所有观察者看到中央卡背与翻牌动画，仅触发者本人可点击。结果使用奖励、罚款、道具、移动、入狱五类卡面和动态说明，展示 3 秒后自动收起，不需要确认按钮。
- 状态机 `core/EventDraw.ts`（IDLE→WAITING→FLIPPING→RESULT→IDLE，纯函数，SelfCheck 覆盖重复点击/他人点击/超时/只结算一次）；时间常量在 `Theme.anim.event*`。
- 演示菜单："事件格抽卡(我)"、"他人抽卡"。
- 设计覆盖审计见 `.discuss/ui-report.md` 末尾"设计覆盖审计"章节（A/B/C 分类、不一致清单、缺失素材/设计清单）。

## 当前 UI 更新（2026-10-06）
- 基线是 `design/screens/` 的棋盘 v7、动作 v8、其余页面 refresh-v3 和补图 supplement-v1。已接入八种头像/棋子、0–3 级房屋、中央小镇、六色按钮、道具卡、事件卡背/牌堆/五类正面、骰子、鳄鱼、牙齿及常用图标。
- 登录资料按03稿调整结构：顶部微信按钮、居中资料标题、全宽昵称及检查提示、方形头像选择格、面板内保存按钮。修复空昵称时头像选择重置；保存后房间和对局保持同一头像。
- 对局：棋盘按视口适配，50/30 格数据保持原定义；四列两排玩家；五项道具横滑，右侧固定总量/箭头/短位置条；认输位于现金与我的资产之间；他人回合保留骰子和等待提示，无投骰按钮。
- 观战：本人头像灰显、明确观战横幅、底部聊天记录/输入入口/语音/返回大厅；没有投骰和用卡入口。
- 结算：奖杯横幅、排名/玩家/净资产三列、原房间入口；买地和升级展示按当前稿调整。虎口拔牙使用鳄鱼/独立牙齿 PNG，并补齐底部参与者头像。
- 原始设计和候选素材不覆盖。重复导入可运行 `node client/tools/sync-design-art.cjs`（从仓库根目录）；SHA-256 对照见 `design/screens/records/client-art-import-2026-10-06.json`。Cocos 自动生成的资源 `.meta` 应和 PNG 一并保留。
- 最新截图位于 `design/screens/records/client-review-fixes-2026-10-06/`。TypeScript、196 项逻辑自检、官方 web-mobile 构建和手机竖屏浏览器检查已执行；微信构建/真机未执行。
- 玩家格可打开对应玩家资产页，显示现金／冻结／净资产及地产／车站数量；现金变化在对应玩家格显示绿色加额或红色减额。事件顶部Toast及资产箭头已移除。整页详情隐藏底层棋盘，关闭恢复；地产租金表标记当前等级，车站使用相同分区。

## 仍与设计稿不同的部分
- 最新道具详情改用设计图 16 的整页结构：点击手牌打开，顶部返回、规则分条、底部关闭；响应类仅关闭。当前复用旧 PNG 进行结构预览，独立大卡／背景／规则面板／按钮仍缺，不能作为严格还原验收。定点移动“选择落点”及其他道具结算尚未实现。统一差异及素材清单见 `design/screens/records/ui-corrections-2026-10-06.md`。
- 这次接入现有独立素材，不是整张设计图贴图：Logo/奖杯横幅、部分功能格（车站、公园、起点等）仍包含代码图形；面板质感及局部间距还不是像素级复刻。
- 骰子与棋子仍为位图上的旋转/缩放/位移动画，未制作完整 3D 面翻转和人物六姿态序列；新目标选择、银行、监狱等补图对应的业务操作流程尚未实现，现有演示入口保留。
- 返回键右移到 x=80 以让出左上"☰"；顶部内容避开右上胶囊占位。
- 棋盘中心回合面板做成**屏幕固定覆盖层**（不随棋盘缩放拖动），保证骰子点击区稳定；30 格为 8×9、50 格为 12×15 的环形网格。
- 普通操作弹窗保留顶部玩家条可见；资产／地产／车站／道具详情覆盖整页并隐藏底层棋盘。
- 虎口拔牙的"危险牙"由客户端随机，仅演示；真实环境由服务端固定且不下发。

## 接入后端时的接口点
- 数据：把 WebSocket 推送的 `SessionView` 映射成 `core/Models.ts` 的 `SessionView`，赋给 `MockStore.session` 后调用 `emit()`；字段名与服务端 record 一致，标 [待服务端] 的（棋盘格表、地产状态、冻结资金、聊天）需服务端补充视图。
- 命令：现在直接改本地数据的地方（买地/升级/出价/抵押/投骰/准备/设置/认输/弃牌/虎口选牙）改成发命令并等事件；倒计时用服务端窗口的 `deadline`（`Countdown.setDeadline`）。
- 登录/分享/语音：ProfileScreen 的微信登录、RoomScreen 的分享邀请与语音条、聊天均为占位按钮。

## 微信构建准备
本地预览通过后，在 Cocos 的"构建发布"选择微信小游戏、竖屏，填写自己的 AppID，并配置微信开发者工具路径。AppSecret 不进入客户端或 Git。微信构建和真机联调尚未执行。
引擎生成的 library、temp、build、local、profiles 不提交；保留 assets、settings、package.json、tsconfig.json、元数据及本说明。

## 2026-10-06 资产与格子详情

特殊土地后续已按20银行、21监狱/游戏中心、22事件/休息区、23车站、25起点分别接入独立布局，使用90件补充素材。银行可切换标签及选择资产查看金额；银行和出狱结算按钮因尚未接服务端明确禁用。事件仍在棋盘中央点击卡背，小游戏保持既有演示交互；查看起点不领奖。TypeScript、202项自检、官方web-mobile构建通过。完整状态与截图见 `design/screens/records/special-land-implementation-2026-10-06.md` 和 `special-land-preview-2026-10-06/`。
按第18张已确认的资产／格子详情布局接入整页：新增17件专用位图，金额／产权／租金读取当前客户端状态。后续本轮已完成浏览器点选、滚动、等级标记、车站布局及连续关闭恢复验证；截图见 `design/screens/records/client-review-fixes-2026-10-06/`。微信真机未验证。素材及初轮记录见 `design/screens/assets-information-v1/MANIFEST.md`、`design/screens/records/information-pages-2026-10-06.md`；后续状态以 `design/screens/records/ui-corrections-2026-10-06.md` 为准。
