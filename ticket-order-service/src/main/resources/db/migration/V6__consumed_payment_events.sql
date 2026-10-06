-- 与付款依据在同一事务保存；消息至少一次投递，业务按eventId幂等。
CREATE TABLE t_consumed_event (
    consumer VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consumed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
