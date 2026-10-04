-- 在原订单保存核实后的付款依据；不改动已经执行过的 V1。
ALTER TABLE t_order
    ADD COLUMN payment_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN paid_at DATETIME(3) NULL,
    ADD UNIQUE KEY uk_order_payment (payment_no),
    ADD CONSTRAINT chk_order_payment_evidence CHECK
        ((payment_no IS NULL AND paid_at IS NULL) OR (payment_no IS NOT NULL AND paid_at IS NOT NULL)),
    DROP CHECK chk_order_status,
    ADD CONSTRAINT chk_order_status CHECK (status IN
        ('STOCK_PENDING','PENDING_PAYMENT','PAYMENT_CONFIRMING','CREATE_FAILED','CLOSING','CLOSED','REVIEW_REQUIRED'));
