-- 先从活动服务 /internal/ticket-tiers/{id}/purchase-rule 获取实际 ticketTierId 和 sessionId。
-- 将下面两个 NULL 改成实际 ID，再在 IDEA 的 ticket_inventory SQL 控制台执行。
-- 本脚本不属于 Flyway；重复执行会因主键重复报错，不会覆盖已有库存。
USE ticket_inventory;

SET @ticket_tier_id = NULL;
SET @session_id = NULL;
SET @total_quantity = 100;

INSERT INTO t_ticket_stock
    (ticket_tier_id, session_id, total_quantity, available_quantity, reserved_quantity, sold_quantity)
VALUES
    (@ticket_tier_id, @session_id, @total_quantity, @total_quantity, 0, 0);

SELECT * FROM t_ticket_stock WHERE ticket_tier_id = @ticket_tier_id;
