# MySQL5.7真机验收：CHECK反转清单（v4）

[未验证-MySQL5.7] 以下是按DDL/夹具推导的真机预期，非真机实测。H2继续执行CHECK，原用例不删。MySQL8.0.16+保留CHECK拒绝（3819），更早8.0按5.7口径。

当前脚本40个CHECK标记用例：38个在5.7应改为接受，2个仍被FK拒绝。必须每例重建独立夹具/rollback，不能把非法数据写入后续测试；不要直接运行H2脚本并只看退出码。正例原24条断言改由JDBC/JUnit读取ok字段/对应查询逐项assert=1，因为5.7的smoke_assert不会拦ok=0。两个assertion用例接受只说明该测试辅助约束无效，不是Store业务输入。

## 预期反转为接受（38项）

- `user_id 0`
- `user_id > 2^53-1`
- `active with deleted_at`
- `unknown user status`
- `empty openid`
- `OPEN without held_code`
- `held_code != room_code`
- `room code 7 digits`
- `short create_request_id`
- `closed without closed_at`
- `closed with unknown reason`
- `open with closed_at`
- `9 players`
- `unknown outcome`
- `time-limit mode without minutes`
- `bankruptcy mode with minutes`
- `ended before started`
- `game_no 0`
- `player row: rank without assets`
- `player row: assets without rank`
- `player row: rank 0`
- `player row: seat 8`
- `player row: bad life_state`
- `log: unsupported content kind`
- `log: sha256 31 bytes`
- `log: plain_len > 8 MiB`
- `chat: bad sec_status`
- `I1 closed with NULL reason`
- `user DELETED but live_mark 1`
- `user ACTIVE but live_mark 0`
- `chat detached without hold`
- `record: draft_sha256 31 bytes`
- `log: game_no 0`
- `chat: msg_no 0`
- `assertion mechanism fires`
- `assertion: NULL subquery fails`
- `R2 user_live 0 matches tombstone but CHECK rejects`
- `R2 sender_live 0 matches tombstone but CHECK rejects`

## CHECK标签但不能直接反转（2项）

- `deleted without deleted_at`：仍由FK拒绝：当前user1有live=1子行，改为live=0违反RESTRICT；单测另用无子行用户验证CHECK缺口。
- `player row: user_live 0`：仍由FK拒绝：当前存活父行只有live=1；新增R2墓碑用例才证明live=0会被5.7接受。

## 不反转与应用替代

唯一键/外键/NOT NULL/严格VARCHAR与窄整数宽度继续拒绝；H2 23505→1062、23506→1452、23503→1451、22001→1406、22003/22004→1264。必须确认STRICT_TRANS_TABLES。新R2墓碑0用例在5.7预期接受，证明复合FK本身不会保证子live=1。

所有业务CHECK（包括脚本尚未覆盖的avatar、max_saved、每个NULL分支/枚举边界）及长度保护的Store单测归属见设计§1.1；用移除CHECK的内存H2副本调用真实Store，非法参数仍应在SQL前被拒且无落库。当前只修订设计/DDL/ops，Store尚未按v4验收。当前聊天不入库，该表规则在未来启用时接线验收，现有DDL/SQL用例继续保留。
