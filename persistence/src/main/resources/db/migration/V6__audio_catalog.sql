-- Audio files live in object storage; only immutable metadata and releases live here.
CREATE TABLE audio_asset (
  asset_id VARCHAR(36) NOT NULL,
  payload LONGTEXT NOT NULL,
  published TINYINT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  CONSTRAINT pk_audio_asset PRIMARY KEY (asset_id)
) ${table_options};
CREATE TABLE audio_catalog (
  version_id BIGINT NOT NULL AUTO_INCREMENT,
  payload LONGTEXT NOT NULL,
  created_at BIGINT NOT NULL,
  CONSTRAINT pk_audio_catalog PRIMARY KEY (version_id)
) ${table_options};
CREATE TABLE audio_active (
  slot VARCHAR(16) NOT NULL,
  version_id BIGINT NOT NULL,
  draft_revision INT NOT NULL,
  draft_payload LONGTEXT,
  CONSTRAINT pk_audio_active PRIMARY KEY (slot)
) ${table_options};
INSERT INTO audio_active (slot, version_id, draft_revision, draft_payload) VALUES ('default', 0, 0, NULL);
