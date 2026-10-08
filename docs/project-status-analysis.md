# 完整项目实现情况分析

日期：2026-10-08（同日晚些时候按实现进展更新，见各节"更新"）。范围：当前本地源码中的 client、server、gateway、persistence、web、design、web-manage。方式：需求与调用链静态核查；初版未修改业务代码、运行构建测试、连接线上或进行微信真机验收。

**更新摘要（2026-10-08 晚）**：参数管理后台 /admin 已上线（4.6）；事件格拆为抽卡 / 幸运 / 不幸三种（3.4）；对局事件日志与聊天已写库、写库失败会重试（4.4）；房间聊天气泡与结算页复制房号已接通（4.2）；停在休息区自动展示休息区页面；engine 580 项、gateway 21 项测试在本机通过（第 5 节）。

## 1. 总体判断

项目已超过单纯界面原型阶段：登录到结算的主链路，以及地产、事件、道具、拍卖、交易和小游戏的联机接入代码都已建立。当前更接近“具备主要玩法的单实例联机测试版本”，不能直接认定全部玩法已验收或已具备可靠生产运行能力。

主要缺口集中在：特殊土地详情与实际操作未统一、语音与音效、完整真机与多人验证。服务重启恢复按用户决定不做（崩溃、升级直接解散对局，不补偿）；动态配置管理后台已上线。源码与若干README进度说明已经不一致，需要以当前调用链为准。

不按文件数量估算完成百分比。实现代码存在、联机入口接通、测试通过、线上实际可用是四种不同证据，不能相互替代。

## 2. 架构职责

- server：Java21纯规则引擎，处理房间和对局状态、随机、时钟、金额、产权、卡牌及各类阻塞流程。Engine具备配置哈希、确定性推进及恢复校验基础。
- gateway：Spring Boot身份、HTTP/WebSocket、串行房间命令、定时驱动、连接状态、聊天、战绩 / 事件日志 / 聊天写库、游戏参数发布（/admin-api）。当前房间状态和登录令牌在内存。
- persistence：数据库迁移资源，不是独立Maven应用。gateway通过db profile启用JDBC和Flyway。
- client：Cocos3.8.8与TypeScript，负责页面、动画、弹窗和命令发送。联机数据虽然写入名为MockStore的对象，但联机分支由服务端视图更新，不能因此认定它都是假数据。
- web：Nginx托管H5游戏构建产物；已有部署文件。
- design：设计稿、素材及部分预览验证记录；存在文件不等于对应页面已逐项验收。
- web-manage：参数管理后台（Vue3 + Element Plus）源码；`npm run build` 输出到 gateway/src/main/resources/static/admin，随后台部署，访问 /admin。

## 3. 已建立实现及联机接入的功能

### 3.1 身份、资料与邀请

测试昵称登录、微信code登录、昵称/头像资料、登录能力查询已有客户端与服务端调用链。OnlineSession根据微信环境和后端能力选择登录方式；ApiController与WechatAuth负责服务端身份交换。

微信邀请分享、分享启动房间参数、H5房间链接复制也已有代码。实际微信登录取决于部署AppID/AppSecret和平台配置；本次没有验证这些环境，也没有真机验证分享入口。

证据：client/assets/scripts/net/OnlineSession.ts、Wx.ts、Http.ts；gateway/.../auth/ApiController.java、WechatAuth.java、UserStore.java。

### 3.2 房间与联机走棋

已有建房、房号加入、离开、准备、房主设置、踢人、开局、测试机器人，以及30/50格模板。WebSocket驱动服务端状态推送，客户端发送意图命令。

投骰、逐格移动、服务器窗口和截止时间、事件驱动的骰子/走棋/入狱动画已接入。存在按玩家与requestId去重、流程窗口校验以及同账号新连接取代旧连接的处理。

证据：gateway/.../ws/GameSocketHandler.java、room/LiveRoom.java、RoomService.java；client/.../net/GameClient.ts、OnlineSession.ts、screens/BoardScreen.ts。

### 3.3 地产、车站与经济

买地、放弃、购买后不立即升级、后续落到自己地产升级、租金、起点奖励、车站计租、资产价值与产权均有规则代码和界面接入。

银行抵押/赎回/结束、应急抵押、第二段债务继续/破产已有联机操作。DebtPopup逐块发送EmergencyMortgage，BankPopup发送BankMortgage/Redeem/FinishBank。

监狱的判定骰、支付出狱和出狱卡都有实际操作弹窗发送命令。因此“监狱规则未实现”不准确；欠缺的是新版土地详情页与该操作流程的统一，详见下文。

证据：server/.../engine/EconomyModule.java、TurnModule.java；client/.../popups/BuyPopup.ts、UpgradePopup.ts、BankPopup.ts、DebtPopup.ts、DebtSecondPopup.ts、JailPopup.ts。

### 3.4 事件、道具、拍卖、交易

已有现金奖励/罚款、获得卡、移动、入狱，以及加盖、降级、去车站、回起点的事件结果；本人抽卡和他人观看动画存在联机提示队列。

更新（2026-10-08）：事件格分三种——抽卡事件（问号，点卡翻开）、幸运格（红卡，奖池只放好事）、不幸格（紫卡，罚款 / 降级 / 后退 / 入狱）；幸运与不幸格停下即自动抽一张翻开。30 格 3 抽卡 + 1 幸运 + 1 不幸，50 格 5 + 2 + 2。事件概率、道具概率、幸运 / 不幸奖池概率和金额均可在管理后台调整。

14种道具已经有服务端类型和规则分支，客户端有主动使用、目标选择、响应、查询结果、弃牌等入口。拍卖/交易卡分别走RequestAuction/RequestTrade，主动道具走UseCard，响应走RespondCard。

拍卖已有联机竞价、服务端截止处理及结果；交易已有申请、接受/拒绝及结果。不是仅有弹窗样式。但本次没有逐张道具、每一种失败分支、并发竞价做实际多人验收，不能称全部场景验证完成。

证据：server/.../engine/CardModule.java、AuctionModule.java、TradeModule.java、EventModule.java、config/RuleConfigs.java（LUCKY / UNLUCKY）；client/.../popups/CardUse.ts、CardResponsePopup.ts、QueryPopups.ts、AuctionPopup.ts、TradePopup.ts；BoardScreen的对应状态处理。

### 3.5 虎口拔牙、结算与观战

小游戏已有服务端MinigameModule，客户端TeethScreen联机分支读取当前选牙玩家、已选牙、窗口，发送PickTooth并读取MinigameEnded结果。演示模式仍保留本地随机，不能与联机模式混为一谈。

破产/认输、限时/破产结束、净资产排名、结算页、返回原房间，以及破产玩家观战已有代码。未发现独立游客加入正在进行的对局观战的产品入口，不应把破产观战等同于完整访客观战系统。

证据：server/.../engine/MinigameModule.java、GameModule.java；client/.../screens/TeethScreen.ts、ResultScreen.ts、BoardScreen.ts。

### 3.6 掉线、托管与聊天

客户端有心跳和退避自动重连；服务端Presence与定期checkConnections将疑似断线/确认掉线/恢复接入引擎。重连发送最新个人视图，而非重新播放全部历史消息。

超时代投、连续超时挂机、主动托管和取消托管已有入口，主动托管在SelfMenuPopup。按观察者生成视图及事件可见性过滤已有代码，可保护手牌、查询结果等私有信息；未逐一进行泄漏测试。

文字输入、快捷短语、房间内广播及聊天频率限制已有实现；破产观战者仍可参与聊天。房间内存保留最近消息供重连补齐；更新（2026-10-08）：每条聊天另写入 chat_message（内容安全检测未接入，记为 UNCHECKED）。

证据：client/.../net/GameClient.ts、popups/SelfMenuPopup.ts、ChatPopup.ts；gateway/.../room/Presence.java、LiveRoom.java；server/.../engine/EventProjector.java。

### 3.7 数据库与部署

V1 迁移定义了app_user、wx_identity、room、game_record、game_record_player、game_log、chat_message七张表；V2 增加game_config、game_config_active（参数发布版本）。更新（2026-10-08）：七张表都已有写入路径。

GameRecords在结束时异步提交，战绩头与玩家明细在同一事务写入，提供最近20局查询；同一写线程还写入每局公开事件日志（game_log，gzip 后的引擎事件日志，可用 Engine.decodeEvents 还原）与聊天（chat_message）。未启用db profile时战绩改为内存保存、日志与聊天不保存，线上是否启用不能凭源码判断（需在云托管确认 SPRING_PROFILES_ACTIVE=db）。

根Dockerfile构建gateway，web/Dockerfile托管H5；client/build存在web-mobile、wechatgame等构建目录，微信产物包含game.json和project.config.json。构建产物存在不能证明当前源码已重新构建、成功上传或真机验收。

## 4. 部分实现和明确缺口

### 4.1 特殊土地详情仍与操作弹窗分离

新版SpecialLandView中银行确认按钮被禁用，原因文字为“银行办理结算尚未接入服务端”；监狱三个操作卡点击只显示“出狱结算尚未接入服务端”等Toast。

但BankPopup和JailPopup已能发送真实命令。这是同一业务有两套入口且新版入口未接通的问题，不是底层银行/监狱规则不存在。需要决定详情页是纯查询，还是在有效操作窗口内承载办理；若承载操作，应复用窗口身份、权限、倒计时和关闭逻辑，不能只把按钮改为可点。

证据：client/.../popups/SpecialLandView.ts:111、138；BankPopup.ts:162；JailPopup.ts:85。

更新（2026-10-08）：详情页改为“能办理就办理，否则只查看”。停在这块银行格且银行窗口开着时，详情页的「确认抵押 / 确认赎回」「结束办理」发送与银行弹窗相同的命令；否则确认按钮置灰并提示“停在银行时才能办理”。被关押且出狱判定窗口开着时，监狱详情页的三张操作卡分别掷判定骰、付费出狱、使用出狱卡；否则提示原因。破产观战的「返回大厅」改为确认后真正离开房间（服务端只允许已淘汰者在对局中离房）。

### 4.2 语音、音效与少数入口占位

实时语音未接入：BottomBar显示“语音尚未开放”，RoomScreen和TeethScreen麦克风仍是演示Toast，未发现语音凭证或房间语音服务链路。

音量设置仍为演示Toast；本次在client/assets/scripts未发现AudioSource/AudioClip/playOneShot或微信音频播放接入。design/screens/audio已有素材记录，但素材存在不代表播放已接入。

更新（2026-10-08）：RoomScreen的聊天气泡已改为打开ChatPopup（房间大厅里也能真实聊天）；ResultScreen的房号复制已调用Wx.copyText（微信剪贴板 / 浏览器剪贴板），失败时提示房号。RoomScreen与TeethScreen的麦克风按钮仍是演示（随语音一起做）。

### 4.3 断网重连已实现，服务重启恢复未实现

在线房间和登录令牌仍存内存。重启gateway会解散游戏并使令牌失效；不支持通过数据库恢复未结束的拍卖、交易、小游戏、资金冻结和计时窗口。用户已决定测试版不做恢复：崩溃、升级直接解散对局，不补偿。

规则引擎具备确定性、快照/回放基础，并不意味着gateway已持久化每步状态。目前没有发现运行中状态或恢复快照写库链路。历史设计中暂停停机计时、恢复准备期的目标尚未落实到实际网关恢复流程。

当前架构要求单实例。直接扩到多实例会出现房间定位、连接归属和状态不共享问题，不能只提高云托管最大实例数。可以先保持单实例，完成持久化和恢复，再评估扩容。

### 4.4 持久化完整性不足

更新（2026-10-08）：战绩、事件日志、聊天写库失败时按 1 秒、5 秒、30 秒、2 分钟、10 分钟退避重试，约 10 分钟后放弃并记错误日志；约束冲突（如发送者已注销）不重试。重试队列只在内存里，进程退出前最多等 10 秒把手头的写完；进程崩溃仍可能丢失未写完的数据（测试版可接受）。

game_log 已按局写入全部公开事件（单局上限 20 万条 / 解压后 8MB，超出不写）；chat_message 已逐条写入。尚未实现：game_log 90 天、聊天 30 天的定时清理，聊天内容安全检测与举报保全。资金账本在引擎中，并非已经落成数据库可审计账本；公开事件日志可作为对局审计与回放的数据源，但不含私有事件（手牌、查询结果）。

### 4.5 微信和正式环境仍需核验

微信登录和分享已有实现，README中“尚未接入”的描述已过时；真实AppID、允许域名、AppSecret、测试登录开关和体验版发布不在本次核查范围。

测试登录默认开启，机器人与测试功能受此开关影响，正式环境需验证实际值。SessionTokens是内存映射，未看到过期与主动撤销生命周期。GameClient令牌也未见持久存储，客户端进程重新启动后的身份/房间恢复需要端到端验证或补齐。

微信onShow当前可处理分享房号；未见完整onHide暂离与返回恢复控制的专门链路。连接心跳机制不能代替Android/iOS切后台、杀进程和网络切换的真实验收。

### 4.6 配置管理和设计验收

更新（2026-10-08）：参数管理后台已上线（/admin，密码来自云托管环境变量 ADMIN_PASSWORD）：土地命名、地产 / 车站价格与租金、事件与道具概率、幸运 / 不幸奖池、事件金额范围、起点 / 小游戏 / 出狱费用；校验后发布，带发布记录和一键回滚。新开房间绑定当前生效版本，进行中的对局不受影响；客户端按房间的 configId 拉取地名与价格表。需要客户端发一次包后，改参数才无需再发包。

资产、土地详情、特殊土地、头像、骰子及移动动画都有实际实现和多次修正记录。但本次仅静态检查，不能认定所有页面严格对齐设计稿，也不能确认最新音效素材、背景和按钮已全部接入。需要按设计编号逐页截图验收，且区分查询页与办理页。

## 5. 文档与测试证据

client/README仍把应急抵押、拍卖、交易、道具、虎口拔牙、微信登录分享、主动托管列为未接入，与当前代码相反。gateway/README也仍写掉线判定、战绩写库未实现，而Presence/checkConnections及GameRecords已有实现。

development-plan与open-decisions含早期“尚未实施”等说明，不适合作为当前进度表；设计README也有旧接入状态。建议另维护带日期、代码入口和验证证据的功能清单，历史规划保留但标明已过时部分。

更新（2026-10-08）：本机（JDK21 + Maven 3.9.11）重跑，server 580 项、gateway 21 项测试全部通过；gateway 含 AdminApiTest、ConfigBindingTest、GameDataDbTest（H2 上验证事件日志与聊天落库）。server 的 golden 快照已用 .gitattributes 固定 LF，Windows 检出不再导致 ReplayTest 失败。客户端有SelfCheck、历史类型检查/构建/浏览器预览记录，不等于所有联机玩法验收。

生产Dockerfile使用maven.test.skip=true，发布构建本身不执行测试；仓库未发现.github测试流水线。测试应在发布之前单独执行，不把镜像成功当成规则测试通过。

## 6. 推荐后续顺序

1. 收口联机主链路：统一新版银行/监狱与真实操作弹窗（待决定详情页是纯查询还是可办理）。房间聊天、房号复制已接通。
2. 多人验收：优先2/4/8人全流程，逐张道具、并发竞价、一口价、交易响应、欠款、小游戏、认输及全员挂机；证明展示与结算一致。
3. 微信真机验收：登录、头像、分享入房、切后台、弱网、重复连接、杀进程重进及Android/iOS布局。语音与音效分别立项，不把按钮存在当功能完成。
4. 运行可靠性：测试版允许崩溃 / 升级丢局（用户决定）；战绩与日志写库重试已补。若准备长期对外运行，再评估对局状态持久化、重启恢复、数据清理任务与备份。
5. 音效：design/screens/audio 已有素材，尚未接入播放；语音等真机 PoC。

初版仅新增此分析文档；2026-10-08 晚的更新随对应代码一同提交。
