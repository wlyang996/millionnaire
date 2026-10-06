# gateway：联机网关（Spring Boot）

把规则引擎（`server/`）包装成可联机的服务：测试登录、房间、WebSocket 推送、定时推进。部署在微信云托管，构建见仓库根目录 `Dockerfile`。

## 配置（云托管环境变量）

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | 80 | 监听端口，须与云托管"端口"一致 |
| `SPRING_PROFILES_ACTIVE` | 无 | 设为 `db` 才连 MySQL（用户、房间写库，启动时 Flyway 建表）；不设则只在内存 |
| `MYSQL_ADDRESS` / `MYSQL_USERNAME` / `MYSQL_PASSWORD` | — | db 配置下必填，用内网地址 |
| `MYSQL_DATABASE` | `millionnaire` | 库名，须事先建好 |
| `TEST_LOGIN_ENABLED` | `true` | 测试身份登录开关；正式上线前设为 `false` |

本地运行：先在 `server/` 执行 `mvn install -Dmaven.test.skip=true`，再在本目录 `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080`。测试：`mvn test`。

## 运行模型
- 房间与对局状态**只在内存**（lean 设计）：服务重启或发版时房间解散，登录令牌失效，客户端需重新登录。
- 每个房间一个引擎实例，同一房间的输入串行处理；接收序号与服务器时间由网关分配，引擎不读系统时钟。
- 回合超时、自动动作由网关按引擎的 `nextWakeUp` 定时发 Tick 推进，不依赖客户端。
- 身份只取自登录令牌：命令里的 `actor` / `playerId` 一律由服务端写入，客户端传了也会被覆盖。
- 必须单实例运行（云托管最小、最大实例数都设为 1）。

## HTTP

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/test-login` | 请求 `{"nickname":"阿杰"}`；返回 `{"token","userId","nickname"}`。每次登录都创建新用户。昵称按引擎规则校验，失败 400 `{"code":"INVALID_NICKNAME"}` |
| GET | `/api/me` | 头 `Authorization: Bearer <token>`；返回 `{"userId","nickname","roomCode"}` |
| GET | `/api/room` | 当前房间快照，形状同 WebSocket 的 `UPDATE`（`events` 为空）；不在房间 404 |
| GET | `/health`、`/health/db` | 存活与数据库诊断 |
| GET | `/dev` | 联机测试台：一个页面开多个玩家，点按钮建房、加入、准备、开局、执行对局命令（页面在 `src/main/resources/static/dev/`） |

`/api/**` 允许跨域（H5 客户端可部署在别的站点），令牌只走 `Authorization` 头，不用 Cookie。

## WebSocket `/ws`

连接：`/ws?token=<token>`（或请求头 `Authorization: Bearer <token>`，或连上后先发 `{"type":"AUTH","token":"..."}`）。
认证成功后服务端发 `HELLO {userId, nickname, roomCode, serverTime}`；若已在房间里，紧接着发一条该房间的 `UPDATE` 快照。
同一用户的新连接会取代旧连接：旧连接收到 `{"type":"REPLACED"}` 后被关闭（4001）。

### 客户端 → 服务端
改变状态的消息必须带 `requestId`（1–64 字符，建议 UUID），服务端回 `RESULT`。**同一 requestId 重发时原样返回上次结论，不会重复执行**（断线重试安全）。

| type | 其他字段 | 说明 |
|---|---|---|
| `CREATE_ROOM` | `settings`（可选） | 建房并自动加入，成为房主；`RESULT.roomCode` 为六位房间号 |
| `JOIN_ROOM` | `roomCode` | 按房间号加入；已在该房间则当作同步 |
| `LEAVE_ROOM` | — | 离开；最后一人离开时房间关闭。对局中只有已淘汰的人能离开 |
| `READY` | `ready`: true/false | 准备 / 取消准备 |
| `UPDATE_SETTINGS` | `settings` | 房主修改设置，全员重新准备 |
| `KICK` | `target`（userId） | 房主开局前移除玩家 |
| `START_GAME` | — | 房主开局（全员准备且人数够） |
| `GAME` | `command`, `args` | 对局命令，见下表 |
| `SET_CONTROL` | `mode`: `MANUAL` / `AWAY` / `HOSTED` | 切换自己的控制模式（暂离 / 托管） |
| `SYNC` | — | 取当前房间的完整快照（不需要 requestId） |
| `PING` | — | 回 `PONG {serverTime}`（不需要 requestId） |

`settings` 形如 `{"boardId":"classic-30","initialCash":3000,"endMode":"TIME_LIMIT","timeLimitMinutes":30,"rollSeconds":15}`（`boardId`：`classic-30` / `classic-50`；`endMode`：`TIME_LIMIT` / `BANKRUPTCY`）。

`GAME` 的 `command` 与 `args`（`windowId` 取自视图 `view.game.windows` 里属于自己的窗口；窗口在 `opensAt` 之前、`deadline` 之后操作都会被拒）：

| command | args |
|---|---|
| `RollDice` | `windowId` |
| `PayBail` | `windowId` |
| `DrawEventCard` | `windowId` |
| `DiscardCard` | `windowId`, `index` |
| `BuyProperty` / `DeclinePurchase` | `windowId` |
| `StartLandAuction` | `windowId`（拍卖属后续里程碑，当前会被拒 `NOT_AVAILABLE`） |
| `UpgradeProperty` / `SkipUpgrade` | `windowId` |
| `BankMortgage` / `Redeem` / `EmergencyMortgage` | `windowId`, `tile` |
| `FinishBank` / `ContinueDebt` / `DeclareBankruptcy` | `windowId` |
| `ResumeControl` / `Surrender` | `gameNo` |

例：`{"type":"GAME","requestId":"8f1c…","command":"RollDice","args":{"windowId":1}}`

### 服务端 → 客户端

| type | 字段 | 说明 |
|---|---|---|
| `RESULT` | `requestId`, `ok`, `outcome`, `code`, `roomCode?`, `message?` | `outcome`：`ACCEPTED` / `REJECTED`（规则拒绝，`code` 为引擎拒绝原因，如 `NOT_YOUR_WINDOW`、`INSUFFICIENT_CASH`）/ `ERROR`（网关错误，`code` 如 `NOT_IN_ROOM`、`ROOM_NOT_FOUND`、`ALREADY_IN_ROOM`、`UNKNOWN_COMMAND`、`UNAUTHENTICATED`、`REQUEST_ID_REQUIRED`） |
| `UPDATE` | `roomCode`, `version`, `serverTime`, `events`, `view` | 房间每推进一步（含定时推进）推送一次：`events` 是本步对你可见的事件（`{"kind","data"}`，用于动画），`view` 是你的最新完整视图（以它为准渲染）。`version` 单调递增 |
| `ROOM_CLOSED` | `roomCode`, `reason` | 房间因故障等原因关闭 |
| `HELLO` / `PONG` / `NO_ROOM` / `REPLACED` | — | 见上文 |

注意：一步的 `UPDATE` 会先于该命令的 `RESULT` 到达。

`view` 为引擎的 `SessionView`：`status`、`hostId`、`members[{playerId,nickname,ready}]`、`settings`、`game`（未开局为 null）、`gamesPlayed`、`lastResult`。
`game` 含 `players`（位置、现金、手牌数、监狱、控制与连接状态）、`board.ownables`、`currentPlayer`、`stage`、`windows[{windowId,kind,owner,opensAt,deadline,paused}]`、`landing`、`debt`、`myHand`（只有自己的手牌）。
倒计时请用 `deadline - serverTime` 结合本地时钟偏差计算，不要只靠本地计时器。

### 断线与重连
- 重连后用同一 token 连接即可：服务端自动发 `HELLO` + 房间快照；也可随时发 `SYNC`。
- 断线期间错过的 `UPDATE` 不补发，以最新快照为准。

## 尚未实现
- 掉线判定（15 秒疑似、30 秒确认）还没接到引擎的连接命令上，目前断线的玩家按"在线但不操作"处理（超时由引擎自动处理）。
- 微信登录（云托管 callContainer 会注入 `X-WX-OPENID`，接入时不需要 AppSecret）。
- 战绩写库（`game_record`）、聊天、房间号加入频率限制、空房间超时关闭。
