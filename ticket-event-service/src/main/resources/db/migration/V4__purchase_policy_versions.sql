ALTER TABLE t_event ADD COLUMN purchase_policy_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE t_purchase_policy (
    event_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    max_sessions INT NOT NULL,
    max_per_order INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    note VARCHAR(200) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(event_id,version),
    CONSTRAINT ck_purchase_policy CHECK(max_sessions>0 AND max_per_order>0 AND status IN ('DRAFT','PUBLISHED'))
) ENGINE=InnoDB;
