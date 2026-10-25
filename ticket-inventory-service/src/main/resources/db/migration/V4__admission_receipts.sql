-- 终态移出活跃账本；轻量凭据长期保留原参数，旧编号不能重新占票。
CREATE TABLE t_stock_admission_receipt (
    order_no VARCHAR(64) PRIMARY KEY,
    ticket_tier_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    seat_fingerprint VARCHAR(64) NOT NULL,
    archived_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_receipt_tier(ticket_tier_id),
    CONSTRAINT ck_receipt_status CHECK(status IN ('APPLIED','CANCELLED','RELEASED'))
) ENGINE=InnoDB;
ALTER TABLE t_stock_admission ADD INDEX idx_admission_archive(expires_at,order_no);
