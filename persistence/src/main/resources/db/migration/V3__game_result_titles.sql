-- Preserve the server's title/metric projection independently of event-log retention.
ALTER TABLE game_record ADD COLUMN result_json MEDIUMTEXT NULL;
