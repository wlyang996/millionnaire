# millionnaire 规则引擎核心（M0）

构建与测试（需 JDK 21，Maven 3.6.1+；`.mvn/maven.config` 自动加载项目级 `.mvn/settings.xml`，以同 id 把不可达的内网镜像覆盖为 Maven Central）：
`export JAVA_HOME=/path/to/jdk21 && export PATH="$JAVA_HOME/bin:$PATH" && mvn -B clean test`

目录（`com.millionnaire.engine`）：`money/` 整数金额与取整，`config/` 规则配置、通用校验 + rules-v1 规格校验、价格表与内容哈希，`random/` 可注入的随机协议（xoshiro256** + Lemire），`time/` 定时任务总排序与窗口，`core/{command,event,state,engine}` 通用内核（输入契约、接收水位、decide→events→evolve、恢复校验、事件投影）与房间领域，`ledger/` 资金账本，`serialize/` 规范序列化、类型登记与格式信封，`replay/` 场景回放、快照续跑、事件重建与随机审计。
测试夹具 `src/test/.../testkit/` 含演示领域（计时窗口、随机、私有事件）、脚本随机源 ScriptedRandom 与跨进程黄金结果入口 GoldenMain；黄金快照在 `src/test/resources/golden/`。
主代码零第三方依赖，测试仅用 JUnit 5；`SourceScanTest` 禁止系统随机数、系统时钟、Hash 集合、`Map.of`、浮点及演示类型进入主代码。
注意：内容哈希与状态哈希是**结构哈希而非语义哈希**——比例 50/100 与 1/2、列表换序都会得到不同哈希（保守地视为不同配置，拒绝续跑）。
