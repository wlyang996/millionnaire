-- =====================================================================
-- millionnaire 持久化层 V1 基线（精简版 Lean，阶段 L1）
-- 设计说明：.discuss/db-design-lean.md
-- 完整版（18 表，已废弃为体验版方案）归档在 persistence/docs-archive/V1__full_baseline.sql.txt
--
-- 前提：对局状态只在内存；服务崩溃/发版时房间与对局直接解散，不做恢复。
--       因此本库只保存：账号与微信身份、房间号与房间元数据、局结束时的战绩、（可选）对局公开事件日志、（可选）聊天。
--
-- 方言约定：
--   * 以 MySQL 8.0（>= 8.0.17：CHECK 生效 + utf8mb4_0900_bin）/ InnoDB 为准；同时限定在 H2 (MODE=MySQL) 可执行的子集。
--     H2 1.4.200 已执行通过（persistence/ops/h2-lean-smoke.sh）；真实 MySQL 未验证 [未验证-MySQL]。
--   * 表选项经 Flyway 占位符 ${table_options} 注入（Maven 资源过滤必须对 db/migration 关闭）：
--       MySQL: ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
--       H2   : 空串
--     utf8mb4_0900_bin 为 NO PAD（'a' 与 'a ' 不相等）；utf8mb4_bin 为 PAD SPACE，故不用。
--     需要"按字节精确相等"的标识符（openid、appid、建房请求 ID、启动 ID）一律 VARBINARY，不依赖排序规则。
--   * 二进制长度检查用 LENGTH()（MySQL = 字节数）；H2 1.4.200 的 OCTET_LENGTH(VARBINARY) 返回 2 倍字节数，故不用。
--   * 时间一律 UTC epoch 毫秒 BIGINT（*_at），由应用时钟写入（单实例）。
--   * 索引单独 CREATE INDEX；外键默认 RESTRICT，无级联。
--   * MySQL DDL 隐式提交，本文件不是可回滚单元：基线中途失败时库内只有本文件的表 → 删库重建后重跑（设计 §9.3）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 用户与微信身份
-- ---------------------------------------------------------------------
CREATE TABLE app_user (
  user_id        BIGINT        NOT NULL,              -- 随机正整数（<= 2^53-1，JS 安全）；引擎 playerId = 十进制字符串
  nickname       VARCHAR(32)   NOT NULL,              -- 可重复；码点长度与零宽/双向控制符由应用校验；注销后改为固定占位
  avatar_id      SMALLINT      NOT NULL DEFAULT 0,
  status         VARCHAR(8)    NOT NULL DEFAULT 'ACTIVE',
  created_at     BIGINT        NOT NULL,
  updated_at     BIGINT        NOT NULL,
  last_login_at  BIGINT        NOT NULL,
  deleted_at     BIGINT        NULL,
  CONSTRAINT pk_app_user PRIMARY KEY (user_id),
  CONSTRAINT ck_app_user_id CHECK (user_id > 0 AND user_id <= 9007199254740991),
  CONSTRAINT ck_app_user_avatar CHECK (avatar_id >= 0 AND avatar_id <= 999),
  CONSTRAINT ck_app_user_status CHECK ((status IN ('ACTIVE', 'BANNED') AND deleted_at IS NULL)
                                    OR (status = 'DELETED' AND deleted_at IS NOT NULL))
) ${table_options};

-- openid -> 用户。注销时删除本行（再登录即新用户）。不存 session_key，不存 unionid（最小必要）。
CREATE TABLE wx_identity (
  app_id         VARBINARY(32) NOT NULL,              -- ASCII 字节，精确相等
  open_id        VARBINARY(64) NOT NULL,
  user_id        BIGINT        NOT NULL,
  created_at     BIGINT        NOT NULL,
  last_login_at  BIGINT        NOT NULL,
  CONSTRAINT pk_wx_identity PRIMARY KEY (app_id, open_id),
  CONSTRAINT uk_wx_identity_user UNIQUE (user_id, app_id),
  CONSTRAINT fk_wx_identity_user FOREIGN KEY (user_id) REFERENCES app_user (user_id),
  CONSTRAINT ck_wx_identity_len CHECK (LENGTH(app_id) >= 1 AND LENGTH(open_id) >= 1)
) ${table_options};

-- ---------------------------------------------------------------------
-- 2. 房间：房间号唯一 + 关闭后失效 + 冷却（无单独的号码表）
--    held_code：房间 OPEN 时等于 room_code；关闭后仍占着号码直到被新建房事务在冷却期满后释放（置 NULL）。
--    UNIQUE(held_code) 保证"占用中或冷却中"的号码全局唯一；多个 NULL 不冲突（MySQL 与 H2 均如此）。
--    按号查房只认 status='OPEN'，因此"关闭后失效"不依赖冷却。
-- ---------------------------------------------------------------------
CREATE TABLE room (
  room_id           BIGINT        NOT NULL,           -- 随机正整数；引擎 roomId = 十进制字符串
  room_code         INT           NOT NULL,           -- 六位房间号（展示时补零）；历史值
  held_code         INT           NULL,               -- 见上
  boot_id           VARBINARY(16) NOT NULL,           -- 建房进程的启动 ID（每次进程启动随机生成）；启动清扫只关"别的进程"的房间
  created_by        BIGINT        NOT NULL,
  create_request_id VARBINARY(16) NOT NULL,           -- 建房幂等键（客户端 UUID 的 16 字节）
  status            VARCHAR(8)    NOT NULL,           -- OPEN / CLOSED（大厅/对局中的区别只在内存）
  games_played      INT           NOT NULL DEFAULT 0, -- 已写入战绩的局数（仅供排查）
  created_at        BIGINT        NOT NULL,
  updated_at        BIGINT        NOT NULL,
  closed_at         BIGINT        NULL,
  close_reason      VARCHAR(16)   NULL,
  CONSTRAINT pk_room PRIMARY KEY (room_id),
  CONSTRAINT uk_room_held_code UNIQUE (held_code),
  CONSTRAINT uk_room_create_request UNIQUE (created_by, create_request_id),
  CONSTRAINT fk_room_creator FOREIGN KEY (created_by) REFERENCES app_user (user_id),
  CONSTRAINT ck_room_id CHECK (room_id > 0 AND room_id <= 9007199254740991),
  CONSTRAINT ck_room_code CHECK (room_code >= 0 AND room_code <= 999999),
  CONSTRAINT ck_room_held CHECK (held_code IS NULL OR held_code = room_code),
  CONSTRAINT ck_room_ids CHECK (LENGTH(boot_id) = 16 AND LENGTH(create_request_id) = 16 AND games_played >= 0),
  CONSTRAINT ck_room_status CHECK (
       (status = 'OPEN' AND held_code IS NOT NULL AND closed_at IS NULL AND close_reason IS NULL)
    OR (status = 'CLOSED' AND closed_at IS NOT NULL
        AND close_reason IN ('ENGINE', 'IDLE', 'UPGRADE', 'RESTART', 'FAULT', 'ADMIN')))
) ${table_options};

CREATE INDEX ix_room_status_boot ON room (status, boot_id);   -- 启动清扫
CREATE INDEX ix_room_closed ON room (closed_at);              -- 关闭房间的保留期清理
CREATE INDEX ix_room_creator ON room (created_by);            -- 注销定位

-- ---------------------------------------------------------------------
-- 3. 战绩：局结束（正常结束或中止）时一次性写入 game_record + 每人一行 game_record_player，同一短事务。
--    幂等键：UNIQUE(room_id, game_no)。只有局真正结束才有行，因此没有 PLAYING 状态。
-- ---------------------------------------------------------------------
CREATE TABLE game_record (
  record_id         BIGINT        NOT NULL AUTO_INCREMENT,
  room_id           BIGINT        NOT NULL,           -- 不设外键：房间行到期清理后战绩仍保留
  game_no           INT           NOT NULL,           -- 引擎 gameNo（房间内单调）
  outcome           VARCHAR(8)    NOT NULL,           -- FINISHED（GameEnded）/ ABORTED（GameAborted 或服务端中止）
  end_reason        VARCHAR(32)   NOT NULL,           -- FINISHED：引擎 reason（当前代码为 TIME_UP / NO_PLAYERS）；ABORTED：UPGRADE / FAULT / ADMIN ...
  end_mode          VARCHAR(16)   NOT NULL,           -- 引擎 EndMode 名
  time_limit_min    SMALLINT      NULL,               -- 限时模式的分钟数；破产模式为 NULL
  board_id          VARCHAR(32)   NOT NULL,
  initial_cash      BIGINT        NOT NULL,
  player_count      SMALLINT      NOT NULL,
  started_at        BIGINT        NOT NULL,           -- GameStarted.startedAt
  ended_at          BIGINT        NOT NULL,           -- 产生终局事件那一步的 Input.serverTime
  engine_version    VARCHAR(64)   NOT NULL,
  config_hash       VARCHAR(128)  NOT NULL,           -- 引擎 configHash() 文本
  created_at        BIGINT        NOT NULL,
  CONSTRAINT pk_game_record PRIMARY KEY (record_id),
  CONSTRAINT uk_game_record_game UNIQUE (room_id, game_no),
  CONSTRAINT ck_game_record_outcome CHECK (outcome IN ('FINISHED', 'ABORTED')),
  CONSTRAINT ck_game_record_mode CHECK ((end_mode = 'TIME_LIMIT' AND time_limit_min IS NOT NULL AND time_limit_min > 0)
                                     OR (end_mode = 'BANKRUPTCY' AND time_limit_min IS NULL)),
  CONSTRAINT ck_game_record_values CHECK (game_no >= 1 AND player_count >= 1 AND player_count <= 8
                                          AND initial_cash >= 0 AND ended_at >= started_at)
) ${table_options};

CREATE INDEX ix_game_record_ended ON game_record (ended_at);   -- 孤儿表头清理

-- 每局每人一行；玩家只看自己的行（+ 表头）。不存昵称快照（界面不展示他人，最小必要）。
CREATE TABLE game_record_player (
  record_id         BIGINT        NOT NULL,
  seat_no           SMALLINT      NOT NULL,           -- GameStarted.seats 中的下标
  user_id           BIGINT        NOT NULL,
  ended_at          BIGINT        NOT NULL,           -- 冗余表头，供"我的最近 20 局"走索引
  finish_rank       SMALLINT      NULL,               -- 并列 1、1、3；中止局为 NULL
  final_net_worth   BIGINT        NULL,               -- Standing.netWorth；中止局为 NULL
  final_cash        BIGINT        NULL,               -- Standing.cash；中止局为 NULL
  life_state        VARCHAR(16)   NOT NULL,           -- 终局时 ALIVE / BANKRUPT / SURRENDERED
  CONSTRAINT pk_game_record_player PRIMARY KEY (record_id, seat_no),
  CONSTRAINT uk_grp_user UNIQUE (record_id, user_id),
  CONSTRAINT fk_grp_record FOREIGN KEY (record_id) REFERENCES game_record (record_id),
  CONSTRAINT ck_grp_seat CHECK (seat_no >= 0 AND seat_no < 8),
  CONSTRAINT ck_grp_life CHECK (life_state IN ('ALIVE', 'BANKRUPT', 'SURRENDERED')),
  CONSTRAINT ck_grp_result CHECK ((finish_rank IS NULL AND final_net_worth IS NULL AND final_cash IS NULL)
                               OR (finish_rank IS NOT NULL AND finish_rank >= 1 AND finish_rank <= 8
                                   AND final_net_worth IS NOT NULL AND final_cash IS NOT NULL))
) ${table_options};

CREATE INDEX ix_grp_user_ended ON game_record_player (user_id, ended_at DESC, record_id DESC);

-- ---------------------------------------------------------------------
-- 4.（可选）对局公开事件日志：局结束时写一行，只含该局 GameStarted..终局之间的 PUBLIC 事件（无手牌、无随机状态）。
--    用于申诉/排查；保留 90 天；不加密（不含秘密）；独立于战绩的保留与删除，故不设外键。
-- ---------------------------------------------------------------------
CREATE TABLE game_log (
  room_id           BIGINT        NOT NULL,
  game_no           INT           NOT NULL,
  outcome           VARCHAR(8)    NOT NULL,
  content_kind      VARCHAR(16)   NOT NULL,           -- PUBLIC_EVENTS（v1 只允许这一种）
  codec             VARCHAR(16)   NOT NULL,           -- GZIP_EVLOG1：gzip(引擎 Codec 对 EventLog 信封的规范文本)
  engine_version    VARCHAR(64)   NOT NULL,
  config_hash       VARCHAR(128)  NOT NULL,
  event_count       INT           NOT NULL,
  plain_len         INT           NOT NULL,           -- 解压后字节数
  plain_sha256      VARBINARY(32) NOT NULL,
  payload           MEDIUMBLOB    NOT NULL,
  ended_at          BIGINT        NOT NULL,
  created_at        BIGINT        NOT NULL,
  CONSTRAINT pk_game_log PRIMARY KEY (room_id, game_no),
  CONSTRAINT ck_game_log_kind CHECK (content_kind = 'PUBLIC_EVENTS' AND codec = 'GZIP_EVLOG1'
                                     AND outcome IN ('FINISHED', 'ABORTED')),
  CONSTRAINT ck_game_log_len CHECK (event_count >= 0 AND plain_len >= 0 AND plain_len <= 8388608
                                    AND LENGTH(plain_sha256) = 32)
) ${table_options};

CREATE INDEX ix_game_log_ended ON game_log (ended_at);   -- 90 天清理

-- ---------------------------------------------------------------------
-- 5.（可选）文字聊天：保留 30 天（待合规核实）；被举报消息 hold_until 保全；固定表情不入库。
-- ---------------------------------------------------------------------
CREATE TABLE chat_message (
  msg_id          BIGINT        NOT NULL AUTO_INCREMENT,
  room_id         BIGINT        NOT NULL,
  sender_user_id  BIGINT        NOT NULL,
  content         VARCHAR(200)  NOT NULL,
  sec_status      VARCHAR(16)   NOT NULL,     -- 内容安全检测结果
  hold_until      BIGINT        NULL,         -- 举报保全到期；非空且未到期时普通清理跳过
  created_at      BIGINT        NOT NULL,
  CONSTRAINT pk_chat_message PRIMARY KEY (msg_id),
  CONSTRAINT ck_chat_message CHECK (sec_status IN ('PASS', 'REVIEW', 'RISKY', 'UNCHECKED'))
) ${table_options};

CREATE INDEX ix_chat_time ON chat_message (created_at);                   -- 30 天清理
CREATE INDEX ix_chat_sender ON chat_message (sender_user_id, created_at); -- 注销定位
CREATE INDEX ix_chat_room_time ON chat_message (room_id, created_at);     -- 举报取证上下文
