-- 成交与无法履约冲正的持久化恢复状态；旧迁移保持不变。
ALTER TABLE t_order
    ADD COLUMN reversal_reason VARCHAR(256) NULL,
    ADD COLUMN reversal_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_order_reversal (reversal_no),
    DROP CHECK chk_order_status,
    ADD CONSTRAINT chk_order_status CHECK (status IN
        ('STOCK_PENDING','PENDING_PAYMENT','PAYMENT_CONFIRMING','PAID','REVERSAL_PENDING','REVERSED',
         'CREATE_FAILED','CLOSING','CLOSED','REVIEW_REQUIRED')),
    ADD CONSTRAINT chk_order_fulfillment CHECK
        ((status NOT IN ('PAID','REVERSAL_PENDING','REVERSED') OR (payment_no IS NOT NULL AND reservation_id IS NOT NULL))
         AND (status <> 'REVERSAL_PENDING' OR reversal_reason IS NOT NULL)
         AND (status <> 'REVERSED' OR reversal_no IS NOT NULL));

-- 上一阶段因关闭竞争暂停的付款订单重新核对库存；其他人工核对记录不自动处理。
UPDATE t_order SET status='PAYMENT_CONFIRMING',next_attempt_at=CURRENT_TIMESTAMP(3),
    lease_token=NULL,lease_until=NULL
WHERE status='REVIEW_REQUIRED' AND payment_no IS NOT NULL AND reservation_id IS NOT NULL;
UPDATE t_order SET next_attempt_at=CURRENT_TIMESTAMP(3)
WHERE status='PAYMENT_CONFIRMING';
