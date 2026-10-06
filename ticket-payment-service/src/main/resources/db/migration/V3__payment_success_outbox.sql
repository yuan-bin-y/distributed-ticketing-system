-- 第二版第一步：可靠保存支付成功事件；HTTP通知暂时继续，MQ发送器后续接入。
CREATE TABLE t_outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定支付编号',
    payload JSON NOT NULL COMMENT '重发使用原消息，不重新生成业务内容',
    trace_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    lease_token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    UNIQUE KEY uk_outbox_type_aggregate (event_type, aggregate_id),
    KEY idx_outbox_due (status, next_attempt_at, id),
    KEY idx_outbox_lease (status, lease_until, id),
    CONSTRAINT chk_outbox_attempt CHECK (attempt_count >= 0),
    CONSTRAINT chk_outbox_state CHECK (
        (status='PENDING' AND lease_token IS NULL AND lease_until IS NULL AND published_at IS NULL)
        OR (status='SENDING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND published_at IS NULL)
        OR (status='PUBLISHED' AND lease_token IS NULL AND lease_until IS NULL AND published_at IS NOT NULL)
        OR (status='REVIEW_REQUIRED' AND lease_token IS NULL AND lease_until IS NULL AND published_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 升级时先停止全部旧Payment实例。所有成功支付（含已HTTP通知或已冲正）保留付款事件。
-- HTTP已送达不等于RabbitMQ已发布，故全部回填为PENDING；本阶段没有MQ发送器。
-- 历史事件没有原请求traceId，使用可重复计算的迁移追踪编号。
INSERT INTO t_outbox_event(event_id,event_type,aggregate_id,payload,trace_id,status,
                          attempt_count,next_attempt_at,created_at)
SELECT MD5(CONCAT('PAYMENT_SUCCEEDED:',payment_no)), 'PAYMENT_SUCCEEDED', payment_no,
       JSON_OBJECT('eventId',MD5(CONCAT('PAYMENT_SUCCEEDED:',payment_no)),
                   'eventType','PAYMENT_SUCCEEDED','schemaVersion',1,
                   'orderNo',order_no,'paymentNo',payment_no),
       MD5(CONCAT('payment-outbox-migration:',payment_no)), 'PENDING', 0,
       CURRENT_TIMESTAMP(3), paid_at
FROM t_payment WHERE status='SUCCESS';
