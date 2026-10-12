# 管理端登录动画接入记录

日期：2026-10-12。状态：已接入本地源码和网关静态产物，纳入本次提交，未部署。

## 来源与许可

- 项目：https://github.com/Y-jtt/westud-login-animation
- 源文件：`main/Login-form-animated.html`，本轮实际下载文件 SHA-256：`D8BEB86F7FB86B8BE1F5A31F961A3D7C76D293BBBF5A913206A426167F50D347`。
- MIT，Copyright (c) 2026 Y-jtt。完整版权和许可文字保存在 `web-manage/public/licenses/westud-login-animation.txt`，构建后对应 `/admin/licenses/westud-login-animation.txt`。
- 四角色 SVG 原始几何图形参考存档：[mascots.svg](admin-login-2026-10-12/mascots.svg)。实际页面由 `web-manage/src/components/LoginMascots.vue` 渲染。

## 实际改动

- 新增 `LoginPage.vue`：桌面左右分栏、中文管理员密码表单、窄屏上下布局、密码显隐、空密码校验、行内错误、请求期间禁用提交、成功动画后进入后台。
- 保留 `POST /admin-api/v1/auth/login` 的 `{ password }` 请求及既有 sessionStorage Token 保存方式。
- 原演示邮箱字段、写死凭证、Google 登录、忘记密码与记住30天占位功能均未接入。
- 复用原角色及弹簧动效；组件卸载时回收动画帧、定时器、事件监听和临时眨眼图形。后台标签页暂停动画；系统减少动画偏好开启时使用静态状态。
- 动画仅使用页面交互状态，不读取输入的密码内容。显示密码时仍保持角色回避。
- 删除原演示全局样式、隐藏系统光标、外部 Google Fonts 及成功演示遮罩；登录页样式仅作用于该组件。
- `npm run build` 已更新 `gateway/src/main/resources/static/admin`。无后端、数据库迁移或依赖版本改动。

## 验证与边界

- `web-manage` 的 `npm run build`：通过。
- `git diff --check`：通过。
- 浏览器使用最终构建产物、本机临时 HTTP 模拟接口和虚构密码，验证空密码、密码显隐、请求中禁用、错误密码反馈、回车提交、认证成功后的庆祝、进入后台和退出后重新挂载登录页。测试接口确认请求仅包含 `password` 字段。
- 本机模拟接口刻意不返回玩法数据，验证范围为登录 UI 流程，不代表真实后台或玩法数据联调通过。
- 1280×720 桌面布局无页面溢出；390×844 窄屏截图见 [mobile.jpg](admin-login-2026-10-12/mobile.jpg)，表单无横向溢出。
- 六轮普通／减少动画模式的挂载、状态切换、销毁资源检查：通过；销毁后动画帧、计时器、监听器、临时眨眼图形均为零，减少动画模式无动画帧请求。该检查使用本机临时 DOM/调度模拟，不等同浏览器性能测量。
- 浏览器检查未记录 JS error/warn；截图：[desktop.jpg](admin-login-2026-10-12/desktop.jpg)。
- 未连接线上认证、未部署，未运行后端 Maven 测试。
