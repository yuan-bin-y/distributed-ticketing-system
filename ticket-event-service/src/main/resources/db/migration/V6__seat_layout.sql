CREATE TABLE t_seat_layout (
    ticket_tier_id BIGINT PRIMARY KEY,session_id BIGINT NOT NULL,layout_hash CHAR(64) NOT NULL,
    regions_json JSON NOT NULL,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
