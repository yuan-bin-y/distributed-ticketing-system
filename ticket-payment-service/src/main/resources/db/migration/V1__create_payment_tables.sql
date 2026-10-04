-- MySQL 8.0.16+；只保存本服务支付事实，不跨库关联订单或库存。
CREATE TABLE t_payment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定支付编号',
    order_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单编号，仅作跨服务业务标识',
    user_id BIGINT NOT NULL,
    amount DECIMAL(18,2) NOT NULL COMMENT '订单服务传入的金额快照，第一版固定人民币',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(3) NOT NULL COMMENT '固定东八区、毫秒精度',
    paid_at DATETIME(3) NULL,
    notify_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NONE',
    next_notify_at DATETIME(3) NULL,
    notify_attempt_count INT NOT NULL DEFAULT 0,
    last_notify_error VARCHAR(256) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_no (payment_no),
    UNIQUE KEY uk_payment_order (order_no),
    KEY idx_payment_notify (notify_status, next_notify_at, id),
    CONSTRAINT chk_payment_user CHECK (user_id > 0),
    CONSTRAINT chk_payment_amount CHECK (amount > 0),
    CONSTRAINT chk_payment_attempt CHECK (notify_attempt_count >= 0),
    CONSTRAINT chk_payment_state CHECK (
        (status = 'CREATED' AND paid_at IS NULL AND notify_status = 'NONE' AND next_notify_at IS NULL)
        OR (status = 'SUCCESS' AND paid_at IS NOT NULL AND notify_status IN ('PENDING','DELIVERED')
            AND next_notify_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_payment_reversal (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reversal_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_id BIGINT NOT NULL COMMENT '本库支付单ID，一单最多全额冲正一次',
    amount DECIMAL(18,2) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_reversal_no (reversal_no),
    UNIQUE KEY uk_reversal_payment (payment_id),
    CONSTRAINT fk_reversal_payment FOREIGN KEY (payment_id) REFERENCES t_payment(id),
    CONSTRAINT chk_reversal_amount CHECK (amount > 0),
    CONSTRAINT chk_reversal_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0),
    CONSTRAINT chk_reversal_state CHECK (
        (status = 'PENDING' AND completed_at IS NULL)
        OR (status = 'SUCCESS' AND completed_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
