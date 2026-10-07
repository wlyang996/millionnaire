# web：H5 客户端托管（微信云托管 nginx 服务）

托管 Cocos「Web 手机端」构建产物，浏览器（含手机、微信内置浏览器）公网打开即可游玩；页面直接连云托管上的后台（`client/assets/scripts/net/Config.ts`）。

## 首次创建服务（只做一次）
云托管控制台 → 服务列表 → 新建服务（如 `web`）→ 代码仓库 `wlyang996/millionnaire`、分支 `prod`、**目标目录 `web`**、Dockerfile 名称 `Dockerfile`、端口 `80` → 发布。
实例规格选最小即可（静态文件，无状态，可以多实例）。

## 每次更新
1. `git pull`，在 Cocos Creator 里构建「Web 手机端」（产物在 `client/build/<构建任务名>/`，如 `web-mobile` 或 `web-mobile-001`）。
2. 双击仓库根目录的 `update-web.bat`：
   - 自动选 `client/build` 下**最近一次构建**的目录（窗口里会列出所有构建目录和构建时间，以及这次用的是哪个）；
   - 如果这份产物比最新的客户端代码还旧，会停下提示先重新构建（防止把旧包当新包推上去）；
   - 复制到 `web/dist/` 后**自动提交并推送当前分支**（只提交 `web/dist`，其他未提交的改动保持原样；推送前先合并远端新提交）。
   - 指定目录：`update-web.bat web-mobile-002`；只复制不提交：`update-web.bat -NoPush`；确定要用旧产物：加 `-Force`。
   - 合并冲突或推送失败时窗口会给出提示，新包已在本地提交，处理后再 `git push` 即可。
3. 在云托管里发布 `web` 服务（或开启推送自动触发）。

注意：推送到 `prod` 会同时触发后台服务的流水线（若开启了自动触发），后台重启会解散进行中的对局。
