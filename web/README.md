# web：H5 客户端托管（微信云托管 nginx 服务）

托管 Cocos「Web 手机端」构建产物，浏览器（含手机、微信内置浏览器）公网打开即可游玩；页面直接连云托管上的后台（`client/assets/scripts/net/Config.ts`）。

## 首次创建服务（只做一次）
云托管控制台 → 服务列表 → 新建服务（如 `web`）→ 代码仓库 `wlyang996/millionnaire`、分支 `prod`、**目标目录 `web`**、Dockerfile 名称 `Dockerfile`、端口 `80` → 发布。
实例规格选最小即可（静态文件，无状态，可以多实例）。

## 每次更新
1. `git pull`，在 Cocos Creator 里构建「Web 手机端」（产物在 `client/build/web-mobile-001/`）。
2. 双击仓库根目录的 `update-web.bat`（等同于 `powershell -ExecutionPolicy Bypass -File web\update-dist.ps1`，把产物复制到 `web/dist/`）。
3. `git add web/dist && git commit -m "Update web build" && git push`，然后在云托管里发布 `web` 服务（或开启推送自动触发）。

注意：推送到 `prod` 会同时触发后台服务的流水线（若开启了自动触发），后台重启会解散进行中的对局。
