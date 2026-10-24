CREATE TABLE t_purchase_request_gate (
    user_id BIGINT NOT NULL,idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(user_id,idempotency_key)
) ENGINE=InnoDB;
CREATE TABLE t_purchase_acceptance (
    request_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id BIGINT NOT NULL,idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_tier_id BIGINT NOT NULL,quantity INT NOT NULL,request_json JSON NOT NULL,rule_json JSON NOT NULL,
    amount DECIMAL(18,2) NOT NULL,expires_at DATETIME(3) NOT NULL,accepted_at DATETIME(3) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACCEPTED',last_error VARCHAR(256),
    attempt_count INT NOT NULL DEFAULT 0,next_attempt_at DATETIME(3) NOT NULL,
    lease_token CHAR(32),lease_until DATETIME(3),trace_parent VARCHAR(55),trace_state VARCHAR(512),
    UNIQUE KEY uk_acceptance_request(user_id,idempotency_key),
    INDEX idx_acceptance_due(status,next_attempt_at),
    CHECK(status IN ('ACCEPTED','CANCELLING','CREATED','REJECTED','REVIEW_REQUIRED')),
    CHECK(quantity>0 AND amount>0 AND attempt_count>=0)
) ENGINE=InnoDB;
CREATE TABLE t_order_outbox (
    event_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    request_id CHAR(32) NOT NULL UNIQUE,status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_token CHAR(32),lease_until DATETIME(3),last_error VARCHAR(256),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_order_outbox_due(status,next_attempt_at),
    CHECK(status IN ('PENDING','PUBLISHING','PUBLISHED','REVIEW_REQUIRED'))
) ENGINE=InnoDB;
CREATE TABLE t_order_outbox_replay (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id CHAR(32) NOT NULL,operator_id BIGINT NOT NULL,
    reason VARCHAR(200) NOT NULL,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
