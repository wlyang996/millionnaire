CREATE TABLE admin_role (
  role_id VARCHAR(24) NOT NULL,
  name VARCHAR(32) NOT NULL,
  description VARCHAR(200) NOT NULL,
  permissions TEXT NOT NULL,
  version BIGINT NOT NULL,
  PRIMARY KEY (role_id)
) ${table_options};
CREATE TABLE admin_release_request (
  request_id VARCHAR(36) NOT NULL,
  module VARCHAR(16) NOT NULL,
  title VARCHAR(100) NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  submitted_by BIGINT NOT NULL,
  approved_by BIGINT NULL,
  status VARCHAR(16) NOT NULL,
  scheduled_at BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  version BIGINT NOT NULL,
  result VARCHAR(1000) NOT NULL,
  PRIMARY KEY (request_id)
) ${table_options};
CREATE INDEX ix_admin_release_due ON admin_release_request(status, scheduled_at);
CREATE TABLE admin_media_object (
  object_key VARCHAR(240) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  metadata MEDIUMTEXT NOT NULL,
  bytes_count BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  trashed_at BIGINT NULL,
  backup_key VARCHAR(240) NULL,
  status VARCHAR(16) NOT NULL,
  version BIGINT NOT NULL,
  PRIMARY KEY (object_key)
) ${table_options};
