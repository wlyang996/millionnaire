-- Immutable whole-character releases. Images live in COS; these tables store metadata only.
CREATE TABLE character_pack (
  pack_id VARCHAR(36) NOT NULL,
  avatar_index INT NOT NULL,
  payload LONGTEXT NOT NULL,
  published TINYINT NOT NULL DEFAULT 0,
  revision INT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  CONSTRAINT pk_character_pack PRIMARY KEY (pack_id)
) ${table_options};
CREATE INDEX ix_character_pack_avatar ON character_pack (avatar_index);

CREATE TABLE character_catalog (
  version_id BIGINT NOT NULL AUTO_INCREMENT,
  payload LONGTEXT NOT NULL,
  created_at BIGINT NOT NULL,
  CONSTRAINT pk_character_catalog PRIMARY KEY (version_id)
) ${table_options};
CREATE TABLE character_catalog_active (
  slot VARCHAR(16) NOT NULL,
  version_id BIGINT NOT NULL,
  CONSTRAINT pk_character_catalog_active PRIMARY KEY (slot)
) ${table_options};
INSERT INTO character_catalog_active (slot, version_id) VALUES ('default', 0);
