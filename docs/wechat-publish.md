# 微信小游戏预览、上传与对象存储资源

## 当前发布方式（2026-10-19）

开发者工具本地运行成功，不代表包体适合手机预览。此次原始构建目录总计 72,822,280 字节，其中 212 张 PNG 共 69,080,692 字节，没有分包。预览、上传报告 Timeout；该错误未证明唯一原因，但包体超限必须先解决。

`client/tools/prepare-wechat-publish.cjs` 将图片独立输出到微信对象存储目录，保留客户端脚本、SpriteFrame/Texture 资源索引和引擎内置资源。构建源不会被覆盖。发布包只有约 3.57 MiB；真实上传后的平台统计仍需核对。

当前环境：

- 环境：`prod-d7gc4t98j17d3e142`
- 桶：`7072-prod-d7gc4t98j17d3e142-1509930808`
- 地域：`ap-shanghai`

没有增加后台接口，也不需要修改后台环境变量、部署后台或申请域名。

## 每次构建与发布

1. 在 Cocos Creator 3.8.8 重新构建微信小游戏，输出 `client/build/wechatgame`。
2. 在仓库根目录运行（需要 Node.js）：

   ```powershell
   node client/tools/prepare-wechat-publish.cjs --env prod-d7gc4t98j17d3e142 --bucket 7072-prod-d7gc4t98j17d3e142-1509930808
   ```

   工具输出两个新目录：`output` 是开发者工具导入目录；`uploadRoot` 是对象存储上传源。默认带时间戳，拒绝覆盖既有目录。源码或素材比构建新、启动模板不匹配、出现未经适配的资源类型或拆包后仍超过 4 MiB 时会失败，不生成可用发布包。

3. 微信云托管 → 对象存储 → 存储管理，在桶根目录上传 `uploadRoot` 内的 **`game-assets` 文件夹**，保持完整子目录结构。不要上传外层 `upload` 文件夹，也不要仅上传图片或压缩包。
4. 等待所有图片上传完成。打开其中一个文件，检查 File ID 与输出清单 `sampleFileID` 对应的路径一致。权限须允许实际小游戏用户读取游戏资源；不要为此向玩家开放上传或删除权限。
5. 微信开发者工具导入 **`output` 发布目录**，AppID 仍为 `wx52ae018677bc4829`。原 `wechatgame` 工程不含拆包适配，继续从原工程点预览仍然会上传大包。
6. 编译后确认标题、背景、头像和棋盘素材正常，再点预览，用手机扫码；成功后再上传体验版本。

新旧图片使用内容摘要目录隔离；不要删除仍在使用的老版本资源。源码变化但图片未变化时，目录摘要保持相同，可以复用已经上传的图片。

## 下载与缓存

小游戏启动时在 Cocos 引擎适配层之前安装微信对象存储下载适配，在资源地址转换管线中只处理 `assets/resources/native/` 图片。逻辑地址 `https://mn-game-assets.invalid/` 是本地路由标记，始终由适配器截获，实际调用 `wx.cloud.downloadFile`，不会请求这个域名，不要将它添加到合法域名。

`cloud://环境.存储桶/game-assets/内容摘要/native/...` 为实际图片 File ID。返回的微信临时文件交给 Cocos 原有文件检查、解析和缓存流程。图片本身逐字节复制，没有重新压缩或修改美术；代码与资源索引继续在主包。现有音乐、音效配置与下载地址保持原样，不在此次拆包范围内。

图片失败会出现 `[MN-ASSET] download.fail`，可据此区分资源未上传、路径或存储读取权限问题。不会退回访问默认后台公网域名。该适配单独在微信发布产物中安装，不影响 Cocos 编辑器和 H5 构建。

## 本次产物及验证边界

已生成：

- 微信发布目录：`F:/work/millionnaire/client/build/wechatgame-publish`
- 对象存储上传源：`F:/work/millionnaire/client/temp/wechat-publish-assets/upload/game-assets`
- 含逐文件 SHA-256 的清单：`F:/work/millionnaire/client/temp/wechat-publish-assets/manifest.json`

原始构建和图片均保留，工具文件与测试提交 Git，构建和暂存目录按项目约定不提交。

2026-10-19 启动修复：微信小游戏运行时不能直接 `require` JSON 配置。发布工具改为生成 `mn-cloud-assets.config.js`（`module.exports`），入口使用 JS 模块加载。现有发布目录已同步修复；云端图片摘要、路径和内容保持不变，无需重新上传图片或重新构建 Cocos。编译后确认不再出现 `mn-cloud-assets.config.json.js is not defined`；真实云图片下载仍需运行验证。

验证命令：`node --test client/tests/wechat-publish.test.cjs`。覆盖分离包、原件保留、超限和模板检查、资源路由隔离、微信云下载、初始化并发、失败不回退和取消处理。本次没有实际上传对象存储、获取预览二维码或完成手机验收；这些不能由模拟测试代替。若合规包仍然出现 Timeout，需要继续检查开发者工具的上传网络链路。

参考：[Cocos Creator 3.8 微信资源管理](https://docs.cocos.com/creator/3.8/manual/zh/editor/publish/publish-wechatgame.html#微信小游戏的资源管理)、[微信云存储 SDK 下载](https://docs.cloudbase.net/storage/sdk)。
