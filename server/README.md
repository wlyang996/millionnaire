# millionnaire 规则引擎核心（M0）

构建与测试（需 JDK 21，Maven 3.6.1+；`.mvn/maven.config` 自动加载项目级 `.mvn/settings.xml`，以同 id 把不可达的内网镜像覆盖为 Maven Central）：
`export JAVA_HOME=/path/to/jdk21 && export PATH="$JAVA_HOME/bin:$PATH" && mvn -B clean test`

目录（`com.millionnaire.engine`）：`money/` 整数金额与取整，`config/` 规则配置、通用校验 + rules-v1 规格校验、价格表与内容哈希，`random/` 可注入的随机协议（xoshiro256** + Lemire），`time/` 定时任务总排序与窗口，`core/{command,event,state,engine}` 通用内核（输入契约、接收水位、decide→events→evolve、恢复校验、事件投影）与房间领域，`ledger/` 资金账本，`serialize/` 规范序列化、类型登记与格式信封，`replay/` 场景回放、快照续跑、事件重建与随机审计。
测试夹具 `src/test/.../testkit/` 含演示领域（计时窗口、随机、私有事件）、脚本随机源 ScriptedRandom 与跨进程黄金结果入口 GoldenMain；黄金快照在 `src/test/resources/golden/`。
主代码零第三方依赖，测试仅用 JUnit 5；`SourceScanTest` 禁止系统随机数、系统时钟、Hash 集合、`Map.of`、浮点及演示类型进入主代码。
注意：内容哈希与状态哈希是**结构哈希而非语义哈希**——比例 50/100 与 1/2、列表换序都会得到不同哈希（保守地视为不同配置，拒绝续跑）。

**提交契约**（外层必须遵守）：`Engine.step` 返回 ACCEPTED 或 REJECTED 时，都要把完整的 `state`、`events` 与幂等结果放在同一事务里持久化。REJECTED 只拒绝该命令本身，输入之前已到期任务的效果（任务触发、超时自动动作）仍要提交并广播。DUPLICATE 和 STALE 不改变任何东西。发生 `KernelFaultException` 时整步不提交，房间停止推进，并保存 `FaultReport`（参考实现见 `RoomRunner`），同一故障输入不得自动重试。客户端命令先经 `admitClient` 再分配序号，可信系统命令（`SystemCommand`）经 `admitSystem`。

## 2026-10-10 好友系列赛

版本engine-0.19.0-friend-series。SeriesConfig默认开放3/5/7局、默认3局，积分10/6/4/3/2/1/0/0；参数、并列算法、机器人开关和最少人数通过管理后台配置。普通单局仍为建房默认。

ConfigureSeries首局前选择，StartGame固定名册与设置，GameEnded以真实名次计分一次，GameAborted不计分，EndSeries在局间结束。系列积分不兑换现金；局间退出保留已有积分，掉线可重连；首局后不接受补位/踢人/改规则。JoinBot是可信系统命令，普通Join不含机器人标记。

SeriesState进入快照/回放与哈希，旧构造/缺失series按无系列处理；跨引擎版本快照不直接续跑。617项测试包含2/4/8人完整系列、双并列算法、资产重置、退出、重连、中止和幂等。

## 2026-10-11 组队合作

版本engine-0.23.0-team-coop。TeamConfig默认关闭，后台配置支持2v2/3v3/4v4及援助数值和开关。个人产权和账本独立、队友免租、交替行动；TeamState固定成员并保存主动援助使用次数。TeamAid/RequestTeamAid均依赖已接受命令的事件凭据，防止伪造转款；主动援助限制投骰前，欠款先等待固定援助窗口，补足后一次付款，超时回原债务路径。援助不计月度收入，不能攻击/查询队友，通过队友路障不消耗路障。

GameResult保留个人成绩并增加队伍成绩；只剩一队结束，到时按存活成员净资产总额排名，同分并列。组队与个人系列赛互斥。647项完整测试通过，普通局/系列赛、快照/回放和历史黄金校验通过。详细边界见[完整记录](../design/screens/records/team-coop-complete-2026-10-11.md)。
