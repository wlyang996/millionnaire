-- 精简版 V1 冒烟正例（H2 MODE=MySQL）。由 h2-lean-smoke.sh 拼接在 V1 之后执行。
-- 用户与身份
INSERT INTO app_user VALUES (1, 'a', 0, 'ACTIVE', 0, 0, 0, NULL);
INSERT INTO app_user VALUES (2, 'a', 0, 'ACTIVE', 0, 0, 0, NULL);          -- 昵称可重复
INSERT INTO app_user VALUES (9007199254740991, 'max', 0, 'ACTIVE', 0, 0, 0, NULL);
INSERT INTO wx_identity VALUES (X'7778', X'6F70656E31', 1, 0, 0);
INSERT INTO wx_identity VALUES (X'7778', X'6F70656E32', 2, 0, 0);
INSERT INTO wx_identity VALUES (X'7778', X'6F70656E3120', 9007199254740991, 0, 0);  -- 'open1 '：字节不同即不同

-- 房间：建房 → 关闭（仍占号）→ 冷却期满后释放旧占用 → 新房间复用号码
INSERT INTO room (room_id,room_code,held_code,boot_id,created_by,create_request_id,status,created_at,updated_at)
  VALUES (10, 42, 42, X'00000000000000000000000000000001', 1, X'0102030405060708090A0B0C0D0E0F10', 'OPEN', 0, 0);
UPDATE room SET status='CLOSED', closed_at=1000, close_reason='ENGINE', updated_at=1000 WHERE room_id=10 AND status='OPEN';
UPDATE room SET held_code=NULL WHERE room_id=10 AND held_code=42;
INSERT INTO room (room_id,room_code,held_code,boot_id,created_by,create_request_id,status,created_at,updated_at)
  VALUES (11, 42, 42, X'00000000000000000000000000000002', 2, X'0102030405060708090A0B0C0D0E0F10', 'OPEN', 2000, 2000);
-- 另一已关闭并已释放号码的房间（多个 NULL 不冲突）
INSERT INTO room (room_id,room_code,held_code,boot_id,created_by,create_request_id,status,created_at,updated_at,closed_at,close_reason)
  VALUES (12, 7, NULL, X'00000000000000000000000000000001', 1, X'0102030405060708090A0B0C0D0E0F11', 'CLOSED', 0, 0, 5, 'RESTART');
-- 启动清扫：关闭别的进程留下的 OPEN 房间
UPDATE room SET status='CLOSED', closed_at=3000, close_reason='RESTART', updated_at=3000
  WHERE status='OPEN' AND boot_id <> X'00000000000000000000000000000003';
-- 按号查房只认 OPEN：此时应查不到
INSERT INTO room (room_id,room_code,held_code,boot_id,created_by,create_request_id,status,created_at,updated_at)
  SELECT 13, 99, 99, X'00000000000000000000000000000003', 1, X'0102030405060708090A0B0C0D0E0F12', 'OPEN', 3000, 3000
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM room WHERE held_code = 42 AND status = 'OPEN');

-- 战绩：正常结束一局（2 人）+ 中止一局（排名/资产为 NULL）
INSERT INTO game_record (room_id,game_no,outcome,end_reason,end_mode,time_limit_min,board_id,initial_cash,player_count,
  started_at,ended_at,engine_version,config_hash,created_at)
  VALUES (11, 1, 'FINISHED', 'TIME_UP', 'TIME_LIMIT', 30, 'classic', 15000, 2, 100, 200, 'engine-0.8.1-m3a', 'h', 200);
INSERT INTO game_record_player VALUES (1, 0, 1, 200, 1, 20000, 5000, 'ALIVE');
INSERT INTO game_record_player VALUES (1, 1, 2, 200, 2, 0, 0, 'BANKRUPT');
INSERT INTO game_record (room_id,game_no,outcome,end_reason,end_mode,time_limit_min,board_id,initial_cash,player_count,
  started_at,ended_at,engine_version,config_hash,created_at)
  VALUES (11, 2, 'ABORTED', 'UPGRADE', 'BANKRUPTCY', NULL, 'classic', 15000, 2, 300, 300, 'engine-0.8.1-m3a', 'h', 300);
INSERT INTO game_record_player VALUES (2, 0, 1, 300, NULL, NULL, NULL, 'ALIVE');
INSERT INTO game_record_player VALUES (2, 1, 2, 300, NULL, NULL, NULL, 'SURRENDERED');
-- 并列 1、1：两行同名次合法
INSERT INTO game_record (room_id,game_no,outcome,end_reason,end_mode,time_limit_min,board_id,initial_cash,player_count,
  started_at,ended_at,engine_version,config_hash,created_at)
  VALUES (11, 3, 'FINISHED', 'TIME_UP', 'TIME_LIMIT', 30, 'classic', 15000, 2, 400, 500, 'engine-0.8.1-m3a', 'h', 500);
INSERT INTO game_record_player VALUES (3, 0, 1, 500, 1, 100, 100, 'ALIVE');
INSERT INTO game_record_player VALUES (3, 1, 2, 500, 1, 100, 100, 'ALIVE');

-- "我的最近 20 局"查询（此处取 2）
CREATE TABLE smoke_check AS
  SELECT p.record_id FROM game_record_player p WHERE p.user_id = 1 ORDER BY p.ended_at DESC, p.record_id DESC LIMIT 2;
-- 写入后修剪：删除用户 1 第 2 条以后的行（演示 N=2；生产 N=20）。窗口函数形式需在 MySQL 再验证。
DELETE FROM game_record_player WHERE user_id = 1 AND record_id IN (
  SELECT record_id FROM (SELECT record_id, ROW_NUMBER() OVER (ORDER BY ended_at DESC, record_id DESC) AS rn
                         FROM game_record_player WHERE user_id = 1) t WHERE t.rn > 2);

-- 可选日志与聊天
INSERT INTO game_log VALUES (11, 1, 'FINISHED', 'PUBLIC_EVENTS', 'GZIP_EVLOG1', 'engine-0.8.1-m3a', 'h', 3, 10,
  X'0000000000000000000000000000000000000000000000000000000000000000', X'1F8B', 200, 200);
INSERT INTO chat_message (room_id,sender_user_id,content,sec_status,hold_until,created_at) VALUES (11, 1, '你好', 'PASS', NULL, 0);
INSERT INTO chat_message (room_id,sender_user_id,content,sec_status,hold_until,created_at) VALUES (11, 2, 'x', 'RISKY', 99999, 0);

-- 注销用户 2：解绑微信、匿名化、删本人战绩行与聊天（保全中的保留）
DELETE FROM wx_identity WHERE user_id = 2;
UPDATE app_user SET nickname='已注销用户', avatar_id=0, status='DELETED', deleted_at=4000, updated_at=4000 WHERE user_id = 2;
DELETE FROM game_record_player WHERE user_id = 2;
DELETE FROM chat_message WHERE sender_user_id = 2 AND (hold_until IS NULL OR hold_until < 4000);

-- 清理：孤儿表头（无明细行）、90 天日志、30 天聊天
DELETE FROM game_record WHERE NOT EXISTS (SELECT 1 FROM game_record_player p WHERE p.record_id = game_record.record_id);
DELETE FROM game_log WHERE ended_at < 100;
DELETE FROM chat_message WHERE created_at < 1 AND (hold_until IS NULL OR hold_until < 1);

-- 断言（失败时以除零报错）
SELECT 1 / (CASE WHEN (SELECT COUNT(*) FROM room WHERE status = 'OPEN') = 1 THEN 1 ELSE 0 END);          -- 只剩房间 13
SELECT 1 / (CASE WHEN (SELECT COUNT(*) FROM room WHERE close_reason = 'RESTART') = 2 THEN 1 ELSE 0 END); -- 11、12
SELECT 1 / (CASE WHEN (SELECT COUNT(*) FROM smoke_check) = 2 THEN 1 ELSE 0 END);
SELECT 1 / (CASE WHEN (SELECT COUNT(*) FROM game_record_player WHERE user_id = 1) = 2 THEN 1 ELSE 0 END); -- 修剪后 2 行
SELECT 1 / (CASE WHEN (SELECT MIN(record_id) FROM game_record_player WHERE user_id = 1) = 2 THEN 1 ELSE 0 END); -- 删掉的是最旧的 1
SELECT 1 / (CASE WHEN (SELECT COUNT(*) FROM game_record) = 2 THEN 1 ELSE 0 END);                         -- 表头 1 成为孤儿被删
SELECT 1 / (CASE WHEN (SELECT COUNT(*) FROM chat_message) = 1 THEN 1 ELSE 0 END);                        -- 保全中的消息保留
SELECT 1 / (CASE WHEN (SELECT COUNT(*) FROM wx_identity) = 2 THEN 1 ELSE 0 END);
SELECT 1 / (CASE WHEN (SELECT LENGTH(boot_id) FROM room WHERE room_id = 13) = 16 THEN 1 ELSE 0 END);    -- LENGTH = 字节数
DROP TABLE smoke_check;
