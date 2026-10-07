# 开发进度与续做清单（2026-10-07）

> 用途：会话额度用尽或中断时，下一个会话从这里继续。每完成一项就更新本文件。
> 依据：用户 2026-10-07 指示"继续做欠款应急抵押，把评审过的没有疑问的都开发吧，最后给我个汇总，哪些完成了，哪些没有"；
> 工作方式：直接在 prod 上修改，分次提交；**每完成一项就推送 prod**（用户 2026-10-07 改为此方式，取代"全部做完再推送"）。
> 推送 prod 可能触发云托管自动发布（会解散进行中的对局），用户已知情。

## 一、代码在哪里

- 每完成一项就推送 `origin/prod`，并镜像到 **`origin/claude/project-quick-analysis-d48z76`**。
- 若容器丢失、本地有未推送的提交，从镜像分支恢复：

  ```bash
  git fetch origin claude/project-quick-analysis-d48z76
  git checkout prod
  git merge --ff-only origin/claude/project-quick-analysis-d48z76
  ```

- 本轮提交（从旧到新，均已推送）：

  | 提交 | 内容 |
  |---|---|
  | 5981053 | 客户端：联机欠款走应急抵押弹窗（设计稿 05） |
  | f21da8b | 网关：掉线判定（15 秒疑似、30 秒确认、重连恢复）、全员掉线 120 秒后中止 |
  | 0ccdcad | 引擎：虎口拔牙（engine-0.11.0-m6a） |
  | 18dc737 | 网关：虎口拔牙联机链路测试 |
  | 24433f3 | 客户端：联机虎口拔牙（设计稿 16）、事件得卡超上限的弃牌弹窗 |
  | 478faf2 | 引擎：道具（主动卡、落点后用卡阶段、响应卡） |
  | fa67e69 | 客户端：联机道具（设计稿 13 / 14 / 04） |
  | 0aee3cb | 引擎：拍卖（指定拍卖地土地拍卖 + 拍卖卡） |

## 二、任务清单

| # | 任务 | 状态 |
|---|---|---|
| 1 | 欠款应急抵押接入联机 | ✅ 完成（5981053） |
| 2 | 掉线判定（网关） | ✅ 完成（f21da8b） |
| 3 | 虎口拔牙（引擎 + 网关 + 客户端） | ✅ 完成（0ccdcad、18dc737、24433f3） |
| 4 | 道具：主动卡与响应卡（不含拍卖卡、交易卡） | ✅ 完成（478faf2、fa67e69） |
| 5 | 拍卖（指定拍卖地 + 拍卖卡） | ✅ 完成（0aee3cb 引擎；客户端见上表最后一条） |
| 6 | 交易卡 | ⬜ 未开始（见第四节） |
| 7 | 给用户中文汇总 | ⬜ 最后做（见第五节） |

## 三、拍卖（任务 5，已完成，留作接口说明）

服务端已提供（正式规则 `EconomyConfig.cardsEnabled = true` 时生效）：

- 命令：`StartLandAuction {windowId}`（买地窗口里"发起拍卖"）、`RequestAuction {tile}`（拍卖卡，随时申请）、`Bid {windowId, amount}`（amount = 封顶即一口价）。
- 视图：`game.auction`（PublicAuction：kind LAND/CARD、tile、seller、initiator、basis、start、minRaise、cap、highBid、highBidder、minimumBid、hardEnd、windowId）。
- 窗口：土地拍卖为 `LAND_AUCTION`（所有者 = 发起人），拍卖卡为 `AUCTION`（所有者 = 卖家）；最后 3 秒延时用新的流程事件 `WindowExtended` 改截止时间。
- 事件：`LandAuctionChosen`、`AuctionRequested`、`AuctionStarted`、`BidPlaced`、`AuctionSettled{winner, tile, price, commission}`、`AuctionPassed{tile}`。
- 冻结资金：`players[].frozen`（最高价冻结）。

客户端（已完成）：

1. `net/Protocol.ts` / `core/Models.ts` / `net/ViewAdapter.ts`：加 `auction` 字段、`WindowExtended` 不需要处理（视图里窗口 deadline 已更新）；`FlowKind` 加 `'LAND_AUCTION'`；`GameCommandName` 加 `'StartLandAuction' | 'RequestAuction' | 'Bid'`。
2. `popups/AuctionPopup.ts`（设计稿 05"地产拍卖"，现为演示）改为联机：参数从 `game.auction` 读；倒计时取拍卖窗口 deadline（延时后要重设）；"出价"发 `Bid`（步进器下限 = minimumBid，步长 = minRaise，上限 cap）；"一口价"发 `Bid amount=cap`；卖家 / 发起人只看不出价；拍卖结束（`game.auction` 消失）自动关闭。所有存活玩家（含非当前玩家）都要弹。
3. `popups/BuyPopup.ts`：指定拍卖地（`tile.auctionLot`）且未到全局时限时加"发起拍卖"按钮 → `StartLandAuction`（设计稿 04 买地页如无该按钮，需在汇总里说明补齐方式）。
4. 拍卖卡：`CardDetailPage` 里拍卖卡"使用" → 选择自己未抵押的地产 / 车站（可复用 `AssetsPopup` 或新做选择页）→ `RequestAuction {tile}`；`popups/CardUse.ts` 的 `cardUsable('AUCTION')` 目前返回"拍卖 / 交易需另行申请"，需改。
5. `net/OnlineSession.ts` 的 `trackCards`：加拍卖提示（谁发起、成交价、流拍）。
6. 验证：`$S/tc` 下 `npx tsc -p tsconfig.json`；SelfCheck（202 项）；用 `$S/rebuild2.sh` + Playwright 截图检查拍卖页（参考 `$S/cards.js`、`$S/teeth.js` 的写法）。`$S` = 会话 scratchpad，换会话后需按"四、验证工具"重建。

## 四、待做：交易卡（任务 6）

已裁决规则（requirements 第 14 节、open-decisions #12）：卖自己任意未抵押资产给指定买家，自定价，**最低标准价值 50%（向上取整）、最高 2.5 倍（向下取整）**，不允许免费赠送；买家 15 秒内同意且足额现金才成交，超时视为拒绝；可非自己回合申请（排队到安全点），消耗主动用卡机会；**交易被拒或超时保留卡、消耗机会**；成交消耗卡；托管不主动交易、不接受交易；交易期间资产锁定；认输延后对象为卖家与买家。

设计思路（沿用拍卖的做法）：

- 命令 `RequestTrade {tile, buyer, price}` → `FlowCoordinator.request(..., FlowKind.TRADE, applicant, tile, buyer, price)`（`FlowRequest` 已带 tile / counterparty / price 字段）+ 新事件 `TradeRequested`（占用机会）。
- 安全点启动：`OverlayModule.start` 里 TRADE 分支 → `TradeModule.begin`：发 `TradeStarted`（锁地块）后 `GameModule.openQueuedOverlay`，窗口所有者 = **买家**（15 秒，`tradeResponseMs`）。
- 买家命令 `AnswerTrade {windowId, accept}`：同意且可用现金足额 → `TradeCompleted`（转账、转产权、消耗交易卡、解锁）；拒绝 / 超时 → `TradeDeclined`（解锁，卡保留）。
- `TurnModule.startNextTurn` 的失效申请取消已预留 `TradeRules.requestStillValid`（目前恒为 true，需实现：卖家存活且持卡、地块仍是其未抵押未锁定资产、买家存活、价格仍在区间内）。
- `EconomyModule.involvedInRunningFlow`：交易进行中卖家与买家延后认输；`flowsRunning` 加交易状态。
- 测试参照 `AuctionTest`、`CardLongGameTest`（随机长局里加交易申请与应答，确保事件重放一致）。
- 客户端：`popups/TradePopup.ts`（设计稿 04"交易确认"）改联机；交易卡"使用" → 选资产、选买家、定价（区间提示）→ `RequestTrade`；买家收到窗口弹确认。

## 五、收尾（任务 7）

1. `cd server && mvn -B -q install`，`cd gateway && mvn -B -q test` 全绿（当前：引擎全部通过；网关 10 项通过）。
2. 推送：每项完成即 `git push origin prod`（会触发云托管发布），再镜像 `git push origin prod:claude/project-quick-analysis-d48z76`。
3. 提醒用户：用 Cocos Creator 重新构建 H5 后执行 `update-web.bat`（自动选最新构建目录、提交并推送 web/dist）；后台随 prod 推送重新部署。
4. 用中文给用户汇总"完成 / 未完成 / 原因 / 需要用户确认的点"（见第六、七节）。

## 六、已按保守方式处理、需要在汇总里告诉用户确认的点

- **建造卡能否用在刚买下的地上**（open-decisions 2026-10-07 明确"留到接入建造卡时再定"）：现按**不能**实现（与"买地后不能马上升级"一致）。
- **落点后用卡阶段**：requirements 写"到达后的用卡阶段限时 15 秒，超时结束回合"。现只在"手动玩家、本回合还有机会、手里有此刻能用的卡"时才开这个阶段，其余直接结束回合；界面在回合提示下加了"结束回合"按钮——**设计稿没有画这个按钮**。
- **房屋保护 / 拒绝购买的响应弹窗**：设计稿只画了"租金响应"，这两个按同一版式补齐。
- **定点移动**：确认面板列出 1～6 格地名可点选；设计稿里棋盘上逐格标号高亮**未实现**。
- **建造卡确认面板**：设计稿 14 没有建造，按降级面板的版式补齐。
- **买地页"发起拍卖"**：设计稿 04 买地页没有这一项，指定拍卖地时在按钮上方补一行"发起拍卖（放弃购买资格）"。
- **拍卖卡选资产页**：设计稿没有，沿用银行列表的行样式（可滚动）。
- **拍卖卡申请在启动前失效**（玩家出局、卡被弃、地块已抵押或易主）：申请直接取消，**已占用的主动用卡机会不退还**。
- 道具开关：`EconomyConfig.cardsEnabled`（正式配置开，旧场景测试配置关），配置哈希因此变化；引擎版本 `engine-0.11.0-m6a`。

## 七、本轮做不了的（需要用户提供条件）

- 微信登录 / 分享：需要小游戏 AppID 与开放平台配置。
- 语音：需要选定语音服务商并做 PoC（open-decisions 仍待定）。
- 自选头像同步到服务端：服务端暂无头像字段（目前按 playerId 稳定取 0～7）。

## 四（附）、验证工具（换会话后重建）

- 类型检查：任意目录 `npm i @cocos/creator-types@3.8.8 typescript@5.8.2`，tsconfig 包含 `client/assets/scripts/**/*.ts` 与 `node_modules/@cocos/creator-types/engine/cc.d.ts`，`strict`、`experimentalDecorators`、`useDefineForClassFields: false`。
- SelfCheck：`npx tsc --target ES2020 --module commonjs --strict --skipLibCheck --outDir out client/assets/scripts/core/SelfCheck.ts && node -e "require('./out/core/SelfCheck.js').runSelfCheck()"`（应为 202 passed）。
- 截图：把 `web/dist` 复制一份，用 SystemJS 方式编译 `client/assets/scripts` 替换 `assets/main/index.*.js` 里的模块，`python3 -m http.server` 起服务，Playwright（Chromium 已预装，`PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers`）打开 `index.html?demo=1`，用 `window.__mn`（go / scenario / ctx / popups）摆状态截图。
