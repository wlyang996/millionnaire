-- Independent management identities; V6 is reserved for the separate audio change.
CREATE TABLE admin_user (
  user_id BIGINT NOT NULL,
  username VARCHAR(32) NOT NULL,
  display_name VARCHAR(32) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(24) NOT NULL,
  status VARCHAR(16) NOT NULL,
  must_change_password TINYINT NOT NULL,
  failures INT NOT NULL,
  locked_until BIGINT NOT NULL,
  version BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  last_login_at BIGINT NOT NULL,
  CONSTRAINT pk_admin_user PRIMARY KEY (user_id),
  CONSTRAINT uk_admin_username UNIQUE (username)
) ${table_options};
CREATE TABLE admin_session (
  session_id VARCHAR(36) NOT NULL,
  token_hash VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  revoked_at BIGINT NULL,
  last_seen_at BIGINT NOT NULL,
  device VARCHAR(160) NOT NULL,
  CONSTRAINT pk_admin_session PRIMARY KEY (session_id),
  CONSTRAINT uk_admin_token_hash UNIQUE (token_hash),
  CONSTRAINT fk_admin_session_user FOREIGN KEY (user_id) REFERENCES admin_user(user_id)
) ${table_options};
CREATE INDEX ix_admin_session_user ON admin_session(user_id, created_at);
CREATE INDEX ix_admin_session_expiry ON admin_session(expires_at);
CREATE TABLE admin_audit (
  event_id VARCHAR(36) NOT NULL,
  actor_user_id BIGINT NULL,
  actor_username VARCHAR(32) NOT NULL,
  actor_name VARCHAR(32) NOT NULL,
  module VARCHAR(32) NOT NULL,
  action VARCHAR(64) NOT NULL,
  object_id VARCHAR(200) NOT NULL,
  result VARCHAR(16) NOT NULL,
  created_at BIGINT NOT NULL,
  request_id VARCHAR(36) NOT NULL,
  detail VARCHAR(1000) NOT NULL,
  CONSTRAINT pk_admin_audit PRIMARY KEY (event_id)
) ${table_options};
CREATE INDEX ix_admin_audit_time ON admin_audit(created_at, event_id);
CREATE INDEX ix_admin_audit_actor ON admin_audit(actor_user_id, created_at);
CREATE TABLE admin_guard (
  slot VARCHAR(32) NOT NULL,
  CONSTRAINT pk_admin_guard PRIMARY KEY(slot)
) ${table_options};
INSERT INTO admin_guard(slot) VALUES('accounts');
CREATE TABLE admin_login_limit (
  source_hash VARCHAR(64) NOT NULL,
  window_started BIGINT NOT NULL,
  attempts INT NOT NULL,
  CONSTRAINT pk_admin_login_limit PRIMARY KEY(source_hash)
) ${table_options};
