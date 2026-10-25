ALTER TABLE t_ticket_stock ADD COLUMN seat_mode TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN layout_hash CHAR(64),ADD COLUMN seat_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE t_stock_admission ADD COLUMN seat_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '';
ALTER TABLE t_stock_reservation ADD COLUMN seat_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '';
CREATE TABLE t_session_seat (
    ticket_tier_id BIGINT NOT NULL,seat_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    region VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,seat_row INT NOT NULL,seat_column INT NOT NULL,ordinal INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',owner_order VARCHAR(64),change_version BIGINT NOT NULL,
    PRIMARY KEY(ticket_tier_id,seat_id),UNIQUE KEY uk_seat_ordinal(ticket_tier_id,ordinal),
    INDEX idx_seat_view(ticket_tier_id,region,seat_row,ordinal),
    CHECK(status IN ('AVAILABLE','HELD','RESERVED','SOLD'))
) ENGINE=InnoDB;
CREATE TABLE t_stock_admission_seat (
    order_no VARCHAR(64) NOT NULL,ticket_tier_id BIGINT NOT NULL,seat_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(order_no,seat_id)
) ENGINE=InnoDB;
