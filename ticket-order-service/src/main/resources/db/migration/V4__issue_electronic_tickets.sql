-- 电子票与订单属于同一服务；出票与订单完成使用同一本地事务。
CREATE TABLE t_ticket (
    id BIGINT NOT NULL AUTO_INCREMENT,
    ticket_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_item_id BIGINT NOT NULL,
    ticket_index INT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'VALID',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_no (ticket_no),
    UNIQUE KEY uk_ticket_item_index (order_item_id,ticket_index),
    CONSTRAINT fk_ticket_order_item FOREIGN KEY (order_item_id) REFERENCES t_order_item(id),
    CONSTRAINT chk_ticket_index CHECK (ticket_index > 0),
    -- 本阶段只出票，核销及作废状态在后续迁移中增加。
    CONSTRAINT chk_ticket_status CHECK (status='VALID')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE t_order
    DROP CHECK chk_order_status,
    ADD CONSTRAINT chk_order_status CHECK (status IN
        ('STOCK_PENDING','PENDING_PAYMENT','PAYMENT_CONFIRMING','PAID','COMPLETED',
         'REVERSAL_PENDING','REVERSED','CREATE_FAILED','CLOSING','CLOSED','REVIEW_REQUIRED')),
    DROP CHECK chk_order_fulfillment,
    ADD CONSTRAINT chk_order_fulfillment CHECK
        ((status NOT IN ('PAID','COMPLETED','REVERSAL_PENDING','REVERSED')
          OR (payment_no IS NOT NULL AND reservation_id IS NOT NULL))
         AND (status <> 'REVERSAL_PENDING' OR reversal_reason IS NOT NULL)
         AND (status <> 'REVERSED' OR reversal_no IS NOT NULL));

-- 上一版本的 PAID 订单也需要出票，下一次后台扫描即可恢复。
UPDATE t_order SET next_attempt_at=CURRENT_TIMESTAMP(3),lease_token=NULL,lease_until=NULL
WHERE status='PAID';
