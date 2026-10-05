# Cocos 开发环境安装记录

核验日期：2026-10-05。

## 已安装
- Cocos Dashboard 2.2.2：F:\tools\Cocos\Dashboard\CocosDashboard.exe。
- Cocos Creator 3.8.8：F:\tools\Cocos\Creator\3.8.8\CocosCreator.exe。
- 启动快捷方式位于 F:\tools\Cocos。
- 项目开发根目录：F:\work\millionnaire；客户端规划目录：client。

## 来源与核验
Dashboard 通过 winget Cocos.CocosDashboard 安装，下载来自 download.cocos.com；安装器 SHA256 已由 winget 核验。
Creator ZIP 地址来自 Cocos 官方下载页面：https://www.cocos.com/creator-download。
Creator 源文件：https://download.cocos.com/CocosCreator/v3.8.8/CocosCreator-v3.8.8-win-121518.zip。
已检查分段范围、合并大小 1,027,270,586 字节，tar 解压正常退出。
ZIP 本地 SHA256：E365030AA4F24B515F499CF093CD86FDF38A0F763B5FBCACB20E96253E0FCC0B。此值是本地文件指纹，不是额外获得的官方发布哈希。
两份主程序签名状态为 Valid，签名方为 Xiamen Yaji Software Co., Ltd.；Creator 主程序版本为 3.8.8。

## 首次使用
1. 双击 F:\tools\Cocos\Cocos Dashboard.lnk，用 Cocos 开发者账号登录。该账号与微信小游戏账号不同。
2. 若编辑器列表未显示 3.8.8，选择“添加本地版本”，选择 F:\tools\Cocos\Creator\3.8.8 中的编辑器。无需再次下载。
3. 后续客户端工程创建或导入到 F:\work\millionnaire\client，保持 Creator 版本一致。
4. 构建微信小游戏时配置 AppID 和已安装微信开发者工具路径；AppSecret 不写客户端。

## 当前验证边界
仅完成软件安装、解压、版本和签名检查。尚未完成 Cocos 账号登录、客户端工程建立、微信构建或真机联调。
安装包和下载分段保留在 F:\tools\Cocos\downloads，本次未删除。