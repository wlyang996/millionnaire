-- =====================================================================
-- V2：游戏参数配置（管理后台 /admin 编辑并发布，新建房间读取当前发布版本）
-- 设计说明：web-manage/admin-config-plan.md（测试版精简：只存发布快照与当前指针，不做草稿协作）
--
-- game_config：每次发布追加一行，已发布内容不再修改；回滚 = 以旧内容再发布一行。
--   payload   规范 JSON（GameSettings：地产档位价格与租金、车站、固定费用、事件金额范围、事件与道具权重、地名）
--   rule_hash 由 payload 生成的引擎规则配置 RuleConfig.contentHash()（地名不进规则，改名不改该值）
-- game_config_active：当前生效版本的指针，每个 slot 一行（测试版只有 'default'）。
--
-- 兼容：MySQL 5.7 / 8.0 与 H2 (MODE=MySQL)；不用 JSON 类型、CHECK、窗口函数；表选项走 ${table_options}。
-- 本表为空时游戏使用内置默认配置（引擎 RuleConfigs.defaultV1() + 设计稿地名）。
-- =====================================================================

CREATE TABLE game_config (
  config_id        BIGINT        NOT NULL AUTO_INCREMENT,
  payload          MEDIUMTEXT    NOT NULL,
  rule_hash        VARCHAR(64)   NOT NULL,
  note             VARCHAR(200)  NOT NULL,
  created_by       VARCHAR(64)   NOT NULL,
  created_at       BIGINT        NOT NULL,
  source_config_id BIGINT        NULL,        -- 回滚时记录被恢复的版本
  CONSTRAINT pk_game_config PRIMARY KEY (config_id)
) ${table_options};

CREATE TABLE game_config_active (
  slot       VARCHAR(16) NOT NULL,
  config_id  BIGINT      NOT NULL,
  updated_at BIGINT      NOT NULL,
  CONSTRAINT pk_game_config_active PRIMARY KEY (slot),
  CONSTRAINT fk_game_config_active FOREIGN KEY (config_id) REFERENCES game_config (config_id)
) ${table_options};
