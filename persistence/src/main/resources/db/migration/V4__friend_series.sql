-- Series totals and per-game points are stored with the corresponding game record transaction.
CREATE TABLE friend_series (
  room_id BIGINT NOT NULL,
  series_no BIGINT NOT NULL,
  total_games INT NOT NULL,
  completed_games INT NOT NULL,
  series_status VARCHAR(24) NOT NULL,
  revision BIGINT NOT NULL,
  config_hash VARCHAR(64) NOT NULL,
  snapshot_json MEDIUMTEXT NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (room_id, series_no)
) ${table_options};

CREATE TABLE friend_series_player (
  room_id BIGINT NOT NULL,
  series_no BIGINT NOT NULL,
  player_id VARCHAR(64) NOT NULL,
  seat_no INT NOT NULL,
  bot INT NOT NULL,
  PRIMARY KEY (room_id, series_no, player_id)
) ${table_options};

CREATE TABLE friend_series_score (
  room_id BIGINT NOT NULL,
  series_no BIGINT NOT NULL,
  game_no BIGINT NOT NULL,
  game_index INT NOT NULL,
  player_id VARCHAR(64) NOT NULL,
  finish_rank INT NOT NULL,
  points INT NOT NULL,
  PRIMARY KEY (room_id, series_no, game_no, player_id)
) ${table_options};
