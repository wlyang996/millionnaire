# Cocos 客户端启动说明

工程路径：F:\work\millionnaire\client。编辑器：Cocos Creator 3.8.8。
使用官方 empty-2d 模板，场景 assets/scenes/Lobby.scene，入口脚本 assets/scripts/LobbyBootstrap.ts。

## 打开与预览
1. 使用刚注册的 Cocos 账号登录 Dashboard。
2. 如编辑器列表为空，添加本地版本 F:\tools\Cocos\Creator\3.8.8。
3. 在项目列表选择“添加 / 导入项目”，选择 F:\work\millionnaire\client，不需要重新创建同名项目。
4. 选择 Creator 3.8.8 打开，等待第一次资源导入和脚本编译。
5. 在资源面板双击 assets/scenes/Lobby.scene，再点击顶部预览按钮。
6. 预期看到“好友桌游”、地图说明、“创建房间”和“房间号加入”两个按钮；点击只更新底部本地状态提示。

## 当前能力
仅为启动工程和本地大厅骨架，不是已完成的高保真美术页面或联机游戏。
没有调用后端，不产生真实房间；没有接入微信身份、分享、实时语音。
下一步接入 Spring Boot 服务的房间 HTTP / WebSocket 接口。

## 微信构建准备
本地大厅预览通过后，在 Cocos 的“构建发布”选择微信小游戏、竖屏，填写自己的 AppID，并配置已安装的微信开发者工具路径。
AppSecret 不进入客户端或 Git。微信构建和真机联调本次尚未执行。
引擎生成的 library、temp、build、local、profiles 不提交；保留 assets、settings、package.json、tsconfig.json、元数据及本说明。

## 本次静态检查
已使用安装版 Cocos 3.8.8 的真实 cc.d.ts 完成 TypeScript 类型检查，并核对项目版本、场景对象引用和脚本 UUID。
尚未在 Cocos 编辑器运行预览、构建微信包或执行真机联调。
