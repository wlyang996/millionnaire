# 项目约定（所有人和 AI 助手共同遵守）

## 数据库迁移（persistence/src/main/resources/db/migration）

- **已执行过的迁移脚本一律不改**，包括注释、空格和换行。Flyway 按文件内容计算校验值，任何改动都会让云托管后台启动失败（`Migration checksum mismatch`）。
- 改表结构：新建下一个版本的脚本，如 `V2__add_xxx.sql`、`V3__...sql`，版本号递增、不复用，文件名后半段写清楚改了什么。
- 只想更新说明文字：写到 `persistence/` 下的文档里，不要改迁移脚本。
- 新脚本要同时能在 MySQL 5.7（云托管）和 H2（MySQL 模式，测试用）上执行；表选项用 `${table_options}` 占位符。提交前在 `gateway/` 跑 `mvn test`（`DbProfileTest`、`RoomFlowTest` 会在 H2 上执行全部迁移）。
- 万一已执行的脚本被误改：用 git 恢复成原内容，不要在数据库上执行 `flyway repair` 掩盖差异。

## 分支与部署

- `prod` 是云托管部署分支，推送可能触发后台和 web 服务自动发布；发布会解散进行中的对局。
- 后台构建前先在 `server/` 执行 `mvn install`，再在 `gateway/` 构建或测试。

## 美术与 UI

见 [design/AGENTS.md](design/AGENTS.md)。
