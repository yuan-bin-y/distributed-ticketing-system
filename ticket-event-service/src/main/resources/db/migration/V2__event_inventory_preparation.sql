ALTER TABLE t_event ADD COLUMN creation_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN creation_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_event_creation_key (creation_key);
-- 历史票档不推断库存已准备好。已有发布活动继续沿用查询契约，新管理入口只发布READY票档。
ALTER TABLE t_ticket_tier
    ADD COLUMN planned_quantity INT NULL COMMENT '初始化请求快照，不是实时可售库存',
    ADD COLUMN preparation_status VARCHAR(24) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN preparation_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN preparation_next_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ADD COLUMN preparation_token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN preparation_lease_until DATETIME(3) NULL,
    ADD COLUMN preparation_error VARCHAR(256) NULL,
    ADD KEY idx_tier_preparation (preparation_status, preparation_next_at),
    ADD CONSTRAINT chk_tier_preparation CHECK (preparation_status IN ('LEGACY','PENDING','READY','REVIEW_REQUIRED')),
    ADD CONSTRAINT chk_tier_preparation_values CHECK (preparation_attempts>=0 AND
        (preparation_status='LEGACY' OR planned_quantity>0 AND planned_quantity IS NOT NULL));
