#!/usr/bin/env bash
# D1.1 DDL 冒烟：在 H2 1.4.200 (MODE=MySQL) 上执行 V1、正例、若干约束负例、undo 后重放。
# 用法：bash persistence/ops/h2-ddl-smoke.sh   （需 JDK 17+ 与本地仓库中的 h2-1.4.200.jar）
# 只证明 H2 语法与 CHECK/UNIQUE/FK 行为；不证明 MySQL 的长度限制、排序规则、锁与事务语义。
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
JAR="${H2_JAR:-$HOME/.m2/repository/com/h2database/h2/1.4.200/h2-1.4.200.jar}"
URL="jdbc:h2:mem:t;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
TMP="$(mktemp -d)"
sed 's/\${table_options}//' "$HERE/../src/main/resources/db/migration/V1__baseline.sql" > "$TMP/v1.sql"
cat "$TMP/v1.sql" "$HERE/h2-smoke-positive.sql" > "$TMP/t.sql"
run() { java -cp "$JAR" org.h2.tools.RunScript -url "$URL" -script "$1" >/dev/null 2>&1; }
run "$TMP/t.sql" && echo "POSITIVE OK" || { echo "POSITIVE FAILED"; exit 1; }
fails=0
neg() { cp "$TMP/t.sql" "$TMP/n.sql"; echo "$2" >> "$TMP/n.sql"; if run "$TMP/n.sql"; then echo "FAIL(accepted): $1"; fails=$((fails+1)); else echo "ok rejected: $1"; fi; }
Z32="X'0000000000000000000000000000000000000000000000000000000000000000'"
neg "room code 7 digits" "INSERT INTO room (room_id,room_code,created_by,create_request_id,owner_user_id,phase,member_count,created_at,updated_at) VALUES (11,1000000,1,X'0102030405060709',1,'LOBBY',1,0,0);"
neg "dup create request" "INSERT INTO room (room_id,room_code,created_by,create_request_id,owner_user_id,phase,member_count,created_at,updated_at) VALUES (11,43,1,X'0102030405060708',1,'LOBBY',1,0,0);"
neg "closed without closed_at" "UPDATE room SET phase='CLOSED' WHERE room_id=10;"
neg "PREP without prep_ends_at" "UPDATE room_head SET status='PREP' WHERE room_id=10;"
neg "RECOVERING without outage" "UPDATE room_head SET status='RECOVERING', current_recovery_id=X'00112233445566778899AABBCCDDEEFF' WHERE room_id=10;"
neg "RECOVERING without attempt" "UPDATE room_head SET status='RECOVERING', outage_started_at=1 WHERE room_id=10;"
neg "ACTIVE with outage" "UPDATE room_head SET outage_started_at=5 WHERE room_id=10;"
neg "negative downtime" "UPDATE room_head SET downtime_used_ms=-1 WHERE room_id=10;"
neg "short token" "UPDATE room_head SET writer_token=X'01' WHERE room_id=10;"
neg "short mac" "UPDATE room_head SET last_batch_mac=X'01' WHERE room_id=10;"
neg "receipt without batch" "INSERT INTO input_receipt VALUES (10,1,2,X'6162636465666768697A','SYSTEM',NULL,'Tick',$Z32,$Z32,0,'ACCEPTED',NULL,1,10,$Z32,X'00',0);"
neg "client receipt without actor" "UPDATE input_receipt SET actor_user_id=NULL WHERE room_id=10;"
neg "dup request id" "INSERT INTO event_batch VALUES (10,1,2,4,1,0,'v',1,1,1,2,10,$Z32,X'00',$Z32,$Z32,NULL,0); INSERT INTO input_receipt VALUES (10,1,2,X'61626364656667686970','SYSTEM',NULL,'Tick',$Z32,$Z32,0,'ACCEPTED',NULL,1,10,$Z32,X'00',0);"
neg "genesis batch with nonzero first index" "INSERT INTO event_batch VALUES (10,1,0,5,1,0,'v',1,1,1,2,10,$Z32,X'00',$Z32,$Z32,NULL,0);"
neg "finished without end_seq" "UPDATE game_record SET status='FINISHED', end_source='LOG' WHERE room_id=10;"
neg "playing with ended_at" "UPDATE game_record SET status='PLAYING', end_reason=NULL, end_source=NULL WHERE room_id=10;"
neg "deleted user without deleted_at" "UPDATE app_user SET status='DELETED';"
neg "purge on open room" "UPDATE room SET purge_state='PURGING' WHERE room_id=10;"
neg "delete room with head (FK restrict)" "DELETE FROM room WHERE room_id=10;"
neg "delete batch with receipt (FK restrict)" "DELETE FROM event_batch WHERE room_id=10 AND input_seq=1;"
neg "lease holder without token" "UPDATE writer_lease SET holder_instance_id='x';"
neg "aborted player with rank" "UPDATE game_record_player SET finish_rank=1;"
neg "dup seat" "INSERT INTO game_record_player VALUES (1,2,0,'b','ABORTED',9,NULL,NULL,NULL,NULL,NULL,NULL);"
neg "cooling without until" "UPDATE room_code_lease SET state='COOLING';"
neg "unknown resolution on CLAIM" "UPDATE room_recovery SET unknown_resolution='COMMITTED' WHERE kind='CLAIM';"
cat "$TMP/v1.sql" "$HERE/undo-V1.sql" "$TMP/v1.sql" > "$TMP/u.sql"
run "$TMP/u.sql" && echo "UNDO+REAPPLY OK" || { echo "UNDO FAILED"; fails=$((fails+1)); }
rm -rf "$TMP"; echo "failures=$fails"; exit $fails
