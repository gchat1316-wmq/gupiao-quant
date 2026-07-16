-- auth_user_alter_serverchan_key.sql
-- 给 auth_user 加 serverchan_send_key 列(默认 SCKEY)

ALTER TABLE auth_user
  ADD COLUMN serverchan_send_key VARCHAR(64) NULL COMMENT '默认 Server酱 SendKey';
