# 手机加入房间超时：连接恢复与排查

## 已确认的证据

用户在手机微信中加入好友新开的房间，输入六位房号后显示“网络超时，请重试”。当前客户端的这条提示来自 `GameClient.request` 的本地 15 秒等待超时，表示没有收到匹配 requestId 的 RESULT；不等于服务端已判定房间不存在、已满或体验权限不足。

代码原先每 5 秒发送 PING，但不检查 PONG 或其他服务端消息是否返回；App 回前台也只恢复表现层。手机网络中断或挂起后，如果系统没有及时回调 close，客户端可能一直保留 open 状态和绿色连接图标，后续请求仍发送到旧连接。自动测试复现了这个缺口；尚无本次手机的请求及服务端日志，不能断言它是本次唯一原因。

## 本轮修复

- 以最后收到的有效服务端消息为依据，连续 15 秒无回复时退出旧连接，进入既有重连流程。
- 手机回前台，以及向长时间无回复的连接发送业务请求前，主动检查连接。
- 尚未超时的请求在新连接打开后沿用原 requestId 重发，继续使用服务端幂等处理；旧连接的延迟回调不能干扰新连接。
- 原生发送同步抛错时进入重连，保留请求；保留原有请求超时时间和退避策略。
- 增加 `ws.request.start`、`ws.request.end`、`ws.request.timeout`、`ws.heartbeat.timeout`、`ws.send.fail` 日志。记录命令、请求编号、等待时间、发送次数、连接状态及无回复时长，不记录令牌、用户资料或完整消息体。

没有修改服务端、数据库、玩法参数或美术资源。

## 验证

`client/tests/connection-recovery.test.cjs` 的 5 项测试覆盖：手机挂起后加入、心跳失联时原请求重发、正常心跳下请求超时诊断、发送同步失败、回前台主动恢复。原实现下前 3 项失败，修复后全部通过；既有网络诊断 7 项、云通道 11 项通过，严格 TypeScript 全项目检查通过。

测试不能代替手机双账号联机验收。新包发布后按下列步骤验证：

1. 两端退出旧体验版本，重新进入新版本，各自用自己的微信登录。
2. 好友创建一个新房间，另一端输入房号加入，确认双方成员列表一致。
3. 加入方切微信聊天或切后台至少 20 秒，再回来检查重连、重新加入与房间状态。
4. 如果仍超时，保留发生时间与加入方 `[MN-NET]` 日志，尤其 `ws.request.timeout`、前后的 `ws.heartbeat.timeout` / `ws.retry` / `ws.hello`。若 PONG 一直正常而 JOIN_ROOM 没有 RESULT，再对照同一时段云托管运行日志检查服务端处理，不能继续直接归因为手机断网。

房间和登录令牌仍在服务进程内存，线上要求同一版本单实例运行（最小、最大实例数均为 1，见 `gateway/README.md`）；尚未读取当前控制台配置，不能声称线上已经满足。

构建命令参数遵循 [Cocos Creator 3.8 命令行发布文档](https://docs.cocos.com/creator/3.8/manual/zh/editor/publish/publish-in-command-line.html)。本地包生成、资源摘要比较与手机验证结果分开记录，见后续构建结果。

## 本地构建结果

- 已使用本机 Creator 3.8.8、现有微信构建配置完成重构建，日志包含 build Task Finished，进程退出码 36（官方定义为构建成功）。保留了既有 DebtPopup／DebtSecondPopup、CardUse／QueryPopups 循环依赖警告；没有借本次修复扩大改动范围。
- 新发布目录：`F:/work/millionnaire/client/build/wechatgame-join-recovery`。已运行拆包工具，主包 3,728,364 字节（3.56 MiB），AppID 为 `wx52ae018677bc4829`；原有 `wechatgame-publish` 保留，不能从该旧目录上传本次修复。
- 已检查产物包含 `ws.heartbeat.timeout`、`ws.request.timeout`、`ws.send.fail` 与 `checkConnection`；图片共 212 张，内容摘要仍为 `8be376e21071177e9dba5645dbd51b9248262301ab88d20598738f52de05dbd7`，File ID 根路径与旧发布包一致，无需重新上传图片。
- 构建配置和日志：`client/temp/join-recovery/`；资源清单：该目录下 `cloud-assets/manifest.json`。生成目录按约定不提交 Git。
- 连接恢复 5 项、网络诊断 7 项、云通道 11 项、拆包与实际 Cocos 管线 9 项，共 32 项测试通过，无跳过；严格 TypeScript 检查与 `git diff --check` 通过。
- 尚未上传体验版本，未推送或部署后台，未在两台手机上验证本次超时是否消失。下一步：开发者工具导入新发布目录 → 编译、上传为新体验版本 → 双方退出旧版后重新进入并测试新房间。
