-- 订单库只管理订单与购买快照，不建立跨服务外键。时间统一为东八区、毫秒精度。
CREATE TABLE t_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    total_amount DECIMAL(18,2) NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expires_at DATETIME(3) NOT NULL,
    next_attempt_at DATETIME(3) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    lease_token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    last_error VARCHAR(256) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_order_user_request (user_id, idempotency_key),
    KEY idx_order_recovery (status, next_attempt_at, id),
    CONSTRAINT chk_order_values CHECK (user_id > 0 AND total_amount > 0 AND attempt_count >= 0),
    CONSTRAINT chk_order_status CHECK (status IN
        ('STOCK_PENDING','PENDING_PAYMENT','CREATE_FAILED','CLOSING','CLOSED','REVIEW_REQUIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_order_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    ticket_tier_id BIGINT NOT NULL,
    ticket_tier_name VARCHAR(128) NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    quantity INT NOT NULL,
    subtotal_amount DECIMAL(18,2) NOT NULL,
    PRIMARY KEY (id),
    -- 本阶段一单一个票档；一次可买多张，未来多票档需新迁移。
    UNIQUE KEY uk_order_item (order_id),
    CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES t_order (id),
    CONSTRAINT chk_item_values CHECK (event_id > 0 AND session_id > 0 AND ticket_tier_id > 0
        AND unit_price > 0 AND quantity > 0 AND subtotal_amount = unit_price * quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
