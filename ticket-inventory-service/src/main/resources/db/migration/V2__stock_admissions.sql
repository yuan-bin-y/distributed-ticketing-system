-- Redis 预扣是准入账本，MySQL 仍保存最终库存及可恢复的预留意图。
ALTER TABLE t_ticket_stock ADD COLUMN admission_epoch VARCHAR(32) NULL,
    ADD COLUMN admission_refresh_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ADD INDEX idx_admission_refresh(admission_refresh_at,ticket_tier_id);
CREATE TABLE t_stock_admission (
    order_no VARCHAR(64) PRIMARY KEY,
    ticket_tier_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_admission_tier(ticket_tier_id,status),
    INDEX idx_admission_expiry(status,expires_at),
    CONSTRAINT ck_admission_quantity CHECK(quantity>0),
    CONSTRAINT ck_admission_status CHECK(status IN ('HELD','APPLIED','CANCELLED','RELEASED'))
) ENGINE=InnoDB;
