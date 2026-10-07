-- 仅用于 V1 基线迁移在 MySQL 上中途失败后的手工回退（MySQL DDL 隐式提交，Flyway 无法自动撤销）。
-- 前提：库中尚无业务数据（基线首次执行失败）。执行后运行 flyway repair（删除失败记录），再重新 migrate。
-- 不放在 db/migration 目录下，避免被 Flyway 当作版本迁移扫描。按"子表 -> 父表"顺序删除；IF EXISTS 使其可重复执行。
DROP TABLE IF EXISTS admin_audit;
DROP TABLE IF EXISTS chat_message;
DROP TABLE IF EXISTS game_record_player;
DROP TABLE IF EXISTS game_record;
DROP TABLE IF EXISTS room_incident;
DROP TABLE IF EXISTS room_recovery;
DROP TABLE IF EXISTS instance_heartbeat;
DROP TABLE IF EXISTS writer_lease;
DROP TABLE IF EXISTS engine_release;
DROP TABLE IF EXISTS rule_config_archive;
DROP TABLE IF EXISTS input_receipt;
DROP TABLE IF EXISTS event_batch;
DROP TABLE IF EXISTS room_head;
DROP TABLE IF EXISTS room_code_lease;
DROP TABLE IF EXISTS room;
DROP TABLE IF EXISTS account_deletion;
DROP TABLE IF EXISTS wx_identity;
DROP TABLE IF EXISTS app_user;
