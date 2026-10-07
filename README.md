# millionnaire：好友联机棋盘游戏

微信小游戏 / H5 好友联机"大富翁"类棋盘游戏。规则引擎、联机网关与 Cocos 客户端均已建立，可在 H5 中联机试玩；部分玩法和 UI 仍在按设计稿接入中。

## 目录

| 目录 | 内容 | 说明 |
|---|---|---|
| [server/](./server/README.md) | 规则引擎核心（Java 21，零第三方依赖） | 确定性 decide→events→evolve 内核、整数资金账本、可注入随机、回放与快照 |
| [gateway/](./gateway/README.md) | 联机网关（Spring Boot） | 测试登录、房间、WebSocket 推送、定时推进；房间状态只在内存，须单实例运行 |
| [persistence/](./persistence/) | Flyway 迁移脚本（MySQL 8 / H2） | 账号、房间、战绩等精简表结构；gateway 以 `db` 配置启用 |
| [client/](./client/README.md) | Cocos Creator 3.8.8 + TypeScript 客户端 | 默认联机；`?demo=1` 为不联网的演示模式 |
| [web/](./web/README.md) | H5 构建产物托管（nginx） | 部署到微信云托管 |
| [design/screens/](./design/screens/README.md) | 素材与 UI 设计稿统一归档 | 新增或确认的素材都保存在这里 |

规划文档：[需求基线](./requirements.md)、[开发计划](./development-plan.md)、[待确认事项](./open-decisions.md)、[设计图](./design-diagrams.md)、[开发环境](./development-environment.md)。

## 构建与测试

需要 JDK 21 和 Maven 3.6.1 及以上版本。

```bash
cd server && mvn -B clean install      # 引擎测试并安装到本地仓库
cd ../gateway && mvn -B test           # 网关测试
```

`server/.mvn/maven.config` 每行只能写一个参数（`-s` 和 `.mvn/settings.xml` 分两行），否则 Maven 3.9 会把整行当作一个路径而报 "settings file does not exist"。

后端镜像由根目录 `Dockerfile` 构建（微信云托管）。

## 分支

- `main`：基线。
- `prod`：云托管部署分支，推送可能触发后台和 web 服务的自动发布，发布会解散进行中的对局。
