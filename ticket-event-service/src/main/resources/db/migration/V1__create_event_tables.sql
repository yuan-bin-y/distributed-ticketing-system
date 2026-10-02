-- MySQL 8.0.16+；时间统一按 Asia/Shanghai 约定保存。
-- 活动服务只保存活动、场次与票档，库存由库存服务管理。

CREATE TABLE t_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '活动主键',
    name VARCHAR(200) NOT NULL COMMENT '活动名称',
    category VARCHAR(30) NOT NULL COMMENT '活动类型，如 CONCERT、SPORT、FESTIVAL',
    cover_url VARCHAR(1024) NULL COMMENT '海报图片地址',
    description TEXT NULL COMMENT '活动介绍',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT、PUBLISHED、OFFLINE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
    PRIMARY KEY (id),
    KEY idx_event_status_created (status, created_at),
    CONSTRAINT chk_event_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
    COMMENT = '活动';

CREATE TABLE t_event_session (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '场次主键',
    event_id BIGINT NOT NULL COMMENT '所属活动',
    name VARCHAR(100) NOT NULL COMMENT '场次名称',
    venue_name VARCHAR(200) NOT NULL COMMENT '场馆名称',
    venue_address VARCHAR(500) NOT NULL COMMENT '场馆地址',
    start_time DATETIME(3) NOT NULL COMMENT '演出开始时间',
    end_time DATETIME(3) NOT NULL COMMENT '演出结束时间',
    sale_start_time DATETIME(3) NOT NULL COMMENT '开售时间',
    sale_end_time DATETIME(3) NOT NULL COMMENT '停售时间，不晚于演出开始',
    purchase_limit INT NOT NULL DEFAULT 1 COMMENT '每个用户在该场次的累计限购张数',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT、PUBLISHED、CANCELLED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
    PRIMARY KEY (id),
    KEY idx_session_event_start (event_id, start_time),
    CONSTRAINT fk_session_event FOREIGN KEY (event_id) REFERENCES t_event (id),
    CONSTRAINT chk_session_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED')),
    CONSTRAINT chk_session_purchase_limit CHECK (purchase_limit > 0),
    CONSTRAINT chk_session_performance_time CHECK (end_time > start_time),
    CONSTRAINT chk_session_sale_time CHECK (sale_end_time > sale_start_time),
    CONSTRAINT chk_session_sale_deadline CHECK (sale_end_time <= start_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
    COMMENT = '活动场次';

CREATE TABLE t_ticket_tier (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '票档主键',
    session_id BIGINT NOT NULL COMMENT '所属场次',
    name VARCHAR(100) NOT NULL COMMENT '票档名称',
    price DECIMAL(10, 2) NOT NULL COMMENT '单张票价，单位元',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '0 禁用，1 启用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tier_session_name (session_id, name),
    CONSTRAINT fk_tier_session FOREIGN KEY (session_id) REFERENCES t_event_session (id),
    CONSTRAINT chk_tier_price CHECK (price > 0),
    CONSTRAINT chk_tier_enabled CHECK (enabled IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
    COMMENT = '场次票档';
