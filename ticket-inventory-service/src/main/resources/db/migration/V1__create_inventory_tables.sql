-- MySQL 8.0.16+；库存属于本服务，不跨库关联活动表。
CREATE TABLE t_ticket_stock (
    ticket_tier_id BIGINT NOT NULL COMMENT '活动服务的票档ID',
    session_id BIGINT NOT NULL COMMENT '活动服务的场次ID',
    total_quantity INT NOT NULL,
    available_quantity INT NOT NULL,
    reserved_quantity INT NOT NULL DEFAULT 0,
    sold_quantity INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (ticket_tier_id),
    KEY idx_stock_session (session_id),
    CONSTRAINT chk_stock_ids CHECK (ticket_tier_id > 0 AND session_id > 0),
    CONSTRAINT chk_stock_quantities CHECK (total_quantity >= 0 AND available_quantity >= 0
        AND reserved_quantity >= 0 AND sold_quantity >= 0),
    CONSTRAINT chk_stock_balance CHECK (CAST(total_quantity AS SIGNED) =
        CAST(available_quantity AS SIGNED) + CAST(reserved_quantity AS SIGNED) + CAST(sold_quantity AS SIGNED))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_stock_reservation (
    reservation_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单服务预先生成的稳定编号',
    session_id BIGINT NOT NULL,
    ticket_tier_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    expires_at DATETIME(3) NOT NULL COMMENT '东八区，后续核对任务使用，不能仅凭此时间释放',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (reservation_id),
    UNIQUE KEY uk_reservation_order (order_id),
    KEY idx_reservation_stock (ticket_tier_id, status),
    KEY idx_reservation_expiry (status, expires_at),
    CONSTRAINT chk_reservation_ids CHECK (session_id > 0 AND ticket_tier_id > 0),
    CONSTRAINT chk_reservation_quantity CHECK (quantity > 0),
    CONSTRAINT chk_reservation_status CHECK (status IN ('RESERVED', 'SOLD', 'RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
