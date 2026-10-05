-- 部署迁移时停止全部 Order 实例，避免旧代码继续创建未占用额度的订单。
CREATE TABLE t_user_session_quota (
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    occupied_quantity BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, session_id),
    CONSTRAINT chk_purchase_quota CHECK (user_id > 0 AND session_id > 0 AND occupied_quantity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE t_order ADD COLUMN quota_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'HELD' COMMENT 'HELD占用购买额度，RELEASED已归还';

-- 已有明确失败、关闭、冲正的订单不占额度；成交和待核对订单继续占用。
UPDATE t_order SET quota_status='RELEASED'
WHERE status IN ('CREATE_FAILED','CLOSED','REVERSED');

-- 缺少快照的历史活跃订单无法准确回填，迁移明确失败，先核对数据再修复迁移。
CREATE TEMPORARY TABLE quota_migration_validation (
    missing_items BIGINT NOT NULL CHECK (missing_items=0)
);
INSERT INTO quota_migration_validation
SELECT COUNT(*) FROM t_order o LEFT JOIN t_order_item i ON i.order_id=o.id
WHERE o.quota_status='HELD' AND i.id IS NULL;
DROP TEMPORARY TABLE quota_migration_validation;

INSERT INTO t_user_session_quota (user_id, session_id, occupied_quantity)
SELECT o.user_id, i.session_id, SUM(i.quantity)
FROM t_order o JOIN t_order_item i ON i.order_id=o.id
WHERE o.quota_status='HELD'
GROUP BY o.user_id, i.session_id;

ALTER TABLE t_order ADD CONSTRAINT chk_order_quota_status
    CHECK (quota_status IN ('HELD','RELEASED'));
