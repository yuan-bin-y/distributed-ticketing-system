ALTER TABLE t_outbox_event
    ADD COLUMN cycle_attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN cycle_started_at DATETIME(3) NULL,
    ADD COLUMN review_reason VARCHAR(64) NULL,
    ADD CONSTRAINT chk_outbox_cycle_attempt CHECK (cycle_attempt_count >= 0);

UPDATE t_outbox_event SET cycle_attempt_count=attempt_count,cycle_started_at=created_at;

-- 重放不修改原事件身份、付款事实或总尝试次数，操作原因与状态变更一起提交。
CREATE TABLE t_outbox_redrive (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_attempt_count INT NOT NULL,
    previous_review_reason VARCHAR(64) NULL,
    operator_name VARCHAR(128) NOT NULL,
    operation_reason VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_outbox_redrive_event(event_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
