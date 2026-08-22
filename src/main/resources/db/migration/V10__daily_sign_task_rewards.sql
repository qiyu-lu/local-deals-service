-- M6B: MySQL-backed daily sign-in facts and per-day task grants.

-- A legacy caller could have written duplicate bitmap-equivalent rows before
-- M6B. Keep the earliest fact before installing the final unique boundary.
DELETE s1 FROM tb_sign s1
JOIN tb_sign s2
  ON s1.user_id = s2.user_id
 AND s1.`date` = s2.`date`
 AND s1.id > s2.id;

ALTER TABLE tb_sign
  ADD UNIQUE KEY uk_sign_user_date (`user_id`, `date`);

ALTER TABLE tb_voucher_campaign
  MODIFY COLUMN grant_mode enum('CLAIM','ADMIN','BOTH','TASK')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL;

ALTER TABLE tb_voucher_grant
  MODIFY COLUMN source enum('USER_CLAIM','ADMIN_GRANT','TASK_REWARD')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  ADD COLUMN idempotency_key varchar(96) CHARACTER SET ascii COLLATE ascii_bin NULL
    AFTER source;

UPDATE tb_voucher_grant
SET idempotency_key = 'ONCE'
WHERE idempotency_key IS NULL OR idempotency_key = '';

ALTER TABLE tb_voucher_grant
  MODIFY COLUMN idempotency_key varchar(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  DROP INDEX uk_voucher_grant_campaign_user,
  ADD UNIQUE KEY uk_voucher_grant_campaign_user_key
    (`campaign_id`, `user_id`, `idempotency_key`);
