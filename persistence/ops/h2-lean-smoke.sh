#!/usr/bin/env bash
# 精简版（Lean）V1 DDL 冒烟：在 H2 1.4.200 (MODE=MySQL) 上执行 V1__lean_baseline.sql、一个正例（含断言）、若干约束负例。
# 用法：bash persistence/ops/h2-lean-smoke.sh   （需 JDK 17+ 与本地仓库中的 h2-1.4.200.jar；可用 H2_JAR 覆盖）
# 只证明 H2 语法与 CHECK/UNIQUE/FK 行为；不证明 MySQL 的排序规则、长度、锁与事务语义 [未验证-MySQL]。
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
JAR="${H2_JAR:-$HOME/.m2/repository/com/h2database/h2/1.4.200/h2-1.4.200.jar}"
URL="jdbc:h2:mem:t;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
TMP="$(mktemp -d)"
sed 's/\${table_options}//' "$HERE/../src/main/resources/db/migration/V1__lean_baseline.sql" > "$TMP/v1.sql"
cat "$TMP/v1.sql" "$HERE/h2-lean-smoke-positive.sql" > "$TMP/t.sql"
run() { java -cp "$JAR" org.h2.tools.RunScript -url "$URL" -script "$1" >"$TMP/out.txt" 2>&1; }
if run "$TMP/t.sql"; then echo "POSITIVE OK"; else echo "POSITIVE FAILED"; cat "$TMP/out.txt"; rm -rf "$TMP"; exit 1; fi
fails=0; total=0
neg() {
  total=$((total+1)); cp "$TMP/t.sql" "$TMP/n.sql"; echo "$2" >> "$TMP/n.sql"
  if run "$TMP/n.sql"; then echo "FAIL(accepted): $1"; fails=$((fails+1)); else echo "ok rejected: $1"; fi
}
B16="X'00000000000000000000000000000009'"
R="INSERT INTO room (room_id,room_code,held_code,boot_id,created_by,create_request_id,status,created_at,updated_at) VALUES"
G="INSERT INTO game_record (room_id,game_no,outcome,end_reason,end_mode,time_limit_min,board_id,initial_cash,player_count,started_at,ended_at,engine_version,config_hash,created_at) VALUES"
# 用户与身份
neg "user_id 0"                         "INSERT INTO app_user VALUES (0,'z',0,'ACTIVE',0,0,0,NULL);"
neg "user_id > 2^53-1"                  "INSERT INTO app_user VALUES (9007199254740992,'z',0,'ACTIVE',0,0,0,NULL);"
neg "deleted without deleted_at"        "UPDATE app_user SET status='DELETED' WHERE user_id=1;"
neg "active with deleted_at"            "UPDATE app_user SET deleted_at=1 WHERE user_id=1;"
neg "unknown user status"               "UPDATE app_user SET status='X' WHERE user_id=1;"
neg "dup openid"                        "INSERT INTO wx_identity VALUES (X'7778', X'6F70656E31', 2, 0, 0);"
neg "second openid same user+app"       "INSERT INTO wx_identity VALUES (X'7778', X'6F70656E39', 1, 0, 0);"
neg "empty openid"                      "INSERT INTO wx_identity VALUES (X'7778', X'', 2, 0, 0);"
neg "identity for missing user (FK)"    "INSERT INTO wx_identity VALUES (X'7778', X'6F70656E38', 777, 0, 0);"
neg "delete user with identity (FK)"    "DELETE FROM app_user WHERE user_id=1;"
# 房间
neg "two holders of same code"          "$R (20, 99, 99, $B16, 1, X'0102030405060708090A0B0C0D0E0FAA', 'OPEN', 0, 0);"
neg "closed-but-cooling code reused"    "$R (20, 42, 42, $B16, 1, X'0102030405060708090A0B0C0D0E0FAA', 'OPEN', 0, 0);"
neg "OPEN without held_code"            "$R (20, 5, NULL, $B16, 1, X'0102030405060708090A0B0C0D0E0FAA', 'OPEN', 0, 0);"
neg "held_code != room_code"            "$R (20, 5, 6, $B16, 1, X'0102030405060708090A0B0C0D0E0FAA', 'OPEN', 0, 0);"
neg "room code 7 digits"                "$R (20, 1000000, 1000000, $B16, 1, X'0102030405060708090A0B0C0D0E0FAA', 'OPEN', 0, 0);"
neg "dup create request"                "$R (20, 5, 5, $B16, 1, X'0102030405060708090A0B0C0D0E0F10', 'OPEN', 0, 0);"
neg "short boot_id"                     "$R (20, 5, 5, X'01', 1, X'0102030405060708090A0B0C0D0E0FAA', 'OPEN', 0, 0);"
neg "short create_request_id"           "$R (20, 5, 5, $B16, 1, X'01', 'OPEN', 0, 0);"
neg "room creator missing (FK)"         "$R (20, 5, 5, $B16, 777, X'0102030405060708090A0B0C0D0E0FAA', 'OPEN', 0, 0);"
neg "closed without closed_at"          "UPDATE room SET status='CLOSED', close_reason='ADMIN' WHERE room_id=13;"
neg "closed with unknown reason"        "UPDATE room SET status='CLOSED', closed_at=1, close_reason='WHATEVER' WHERE room_id=13;"
neg "open with closed_at"               "UPDATE room SET closed_at=1 WHERE room_id=13;"
# 战绩
neg "dup game record (idempotency key)" "$G (11, 2, 'ABORTED', 'UPGRADE', 'BANKRUPTCY', NULL, 'classic', 1, 2, 0, 0, 'v', 'h', 0);"
neg "9 players"                         "$G (11, 9, 'FINISHED', 'TIME_UP', 'BANKRUPTCY', NULL, 'classic', 1, 9, 0, 0, 'v', 'h', 0);"
neg "unknown outcome"                   "$G (11, 9, 'PLAYING', 'TIME_UP', 'BANKRUPTCY', NULL, 'classic', 1, 2, 0, 0, 'v', 'h', 0);"
neg "time-limit mode without minutes"   "$G (11, 9, 'FINISHED', 'TIME_UP', 'TIME_LIMIT', NULL, 'classic', 1, 2, 0, 0, 'v', 'h', 0);"
neg "bankruptcy mode with minutes"      "$G (11, 9, 'FINISHED', 'TIME_UP', 'BANKRUPTCY', 30, 'classic', 1, 2, 0, 0, 'v', 'h', 0);"
neg "ended before started"              "$G (11, 9, 'FINISHED', 'TIME_UP', 'BANKRUPTCY', NULL, 'classic', 1, 2, 10, 5, 'v', 'h', 0);"
neg "game_no 0"                         "$G (11, 0, 'FINISHED', 'TIME_UP', 'BANKRUPTCY', NULL, 'classic', 1, 2, 0, 0, 'v', 'h', 0);"
neg "player row: rank without assets"   "INSERT INTO game_record_player VALUES (3, 2, 9007199254740991, 500, 3, NULL, NULL, 'ALIVE');"
neg "player row: assets without rank"   "INSERT INTO game_record_player VALUES (3, 2, 9007199254740991, 500, NULL, 1, 1, 'ALIVE');"
neg "player row: rank 0"                "INSERT INTO game_record_player VALUES (3, 2, 9007199254740991, 500, 0, 1, 1, 'ALIVE');"
neg "player row: seat 8"                "INSERT INTO game_record_player VALUES (3, 8, 9007199254740991, 500, 3, 1, 1, 'ALIVE');"
neg "player row: dup seat"              "INSERT INTO game_record_player VALUES (3, 0, 9007199254740991, 500, 3, 1, 1, 'ALIVE');"
neg "player row: dup user"              "INSERT INTO game_record_player VALUES (3, 5, 1, 500, 3, 1, 1, 'ALIVE');"
neg "player row: bad life_state"        "INSERT INTO game_record_player VALUES (3, 5, 9007199254740991, 500, 3, 1, 1, 'DEAD');"
neg "player row: missing record (FK)"   "INSERT INTO game_record_player VALUES (999, 0, 1, 500, 1, 1, 1, 'ALIVE');"
neg "delete record with players (FK)"   "DELETE FROM game_record WHERE record_id=3;"
# 日志与聊天
neg "log: unsupported content kind"     "INSERT INTO game_log VALUES (11, 3, 'FINISHED', 'FULL_EVENTS', 'GZIP_EVLOG1', 'v', 'h', 0, 0, X'0000000000000000000000000000000000000000000000000000000000000000', X'00', 0, 0);"
neg "log: sha256 31 bytes"              "INSERT INTO game_log VALUES (11, 3, 'FINISHED', 'PUBLIC_EVENTS', 'GZIP_EVLOG1', 'v', 'h', 0, 0, X'00000000000000000000000000000000000000000000000000000000000000', X'00', 0, 0);"
neg "log: plain_len > 8 MiB"            "INSERT INTO game_log VALUES (11, 3, 'FINISHED', 'PUBLIC_EVENTS', 'GZIP_EVLOG1', 'v', 'h', 0, 8388609, X'0000000000000000000000000000000000000000000000000000000000000000', X'00', 0, 0);"
neg "log: dup (room, game)"             "INSERT INTO game_log VALUES (11, 1, 'FINISHED', 'PUBLIC_EVENTS', 'GZIP_EVLOG1', 'v', 'h', 0, 0, X'0000000000000000000000000000000000000000000000000000000000000000', X'00', 0, 0);"
neg "chat: bad sec_status"              "INSERT INTO chat_message (room_id,sender_user_id,content,sec_status,created_at) VALUES (11, 1, 'x', 'OK', 0);"
neg "chat: content > 200 chars"         "INSERT INTO chat_message (room_id,sender_user_id,content,sec_status,created_at) VALUES (11, 1, REPEAT('x', 201), 'PASS', 0);"
# 对照组：与负例只差一处的合法语句必须被接受（防止负例因语法错误而"假通过"）；以及断言机制本身会报错
ctl() {
  total=$((total+1)); cp "$TMP/t.sql" "$TMP/c.sql"; echo "$2" >> "$TMP/c.sql"
  if run "$TMP/c.sql"; then echo "ok accepted: $1"; else echo "FAIL(rejected control): $1"; fails=$((fails+1)); fi
}
ctl "control room"   "$R (20, 98, 98, $B16, 1, X'0102030405060708090A0B0C0D0E0FAA', 'OPEN', 0, 0);"
ctl "control close"  "UPDATE room SET status='CLOSED', closed_at=1, close_reason='ADMIN' WHERE room_id=13;"
ctl "control record" "$G (11, 9, 'FINISHED', 'TIME_UP', 'BANKRUPTCY', NULL, 'classic', 1, 2, 0, 0, 'v', 'h', 0);"
ctl "control player" "INSERT INTO game_record_player VALUES (3, 2, 9007199254740991, 500, 3, 1, 1, 'ALIVE');"
ctl "control log"    "INSERT INTO game_log VALUES (11, 3, 'FINISHED', 'PUBLIC_EVENTS', 'GZIP_EVLOG1', 'v', 'h', 0, 0, X'0000000000000000000000000000000000000000000000000000000000000000', X'00', 0, 0);"
ctl "control chat"   "INSERT INTO chat_message (room_id,sender_user_id,content,sec_status,created_at) VALUES (11, 1, REPEAT('x', 200), 'PASS', 0);"
ctl "control wx"     "INSERT INTO wx_identity VALUES (X'7778', X'6F70656E39', 2, 0, 0);"
neg "assertion mechanism fires"         "SELECT 1 / (CASE WHEN 1 = 2 THEN 1 ELSE 0 END);"
rm -rf "$TMP"; echo "cases=$total failures=$fails"; exit $fails
