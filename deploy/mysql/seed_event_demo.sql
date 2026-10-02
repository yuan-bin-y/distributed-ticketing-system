-- 可选的本地演示数据；建表成功后手动执行一次，不属于 Flyway 迁移。
-- 重复执行会再创建一组数据；使用当前连接的事务提交整组记录。
USE ticket_event;
SET time_zone = '+08:00';
START TRANSACTION;

INSERT INTO t_event (name, category, description, status)
VALUES ('城市音乐节', 'FESTIVAL', '本地活动查询演示数据。', 'PUBLISHED');
SET @demo_event_id = LAST_INSERT_ID();

INSERT INTO t_event_session (
    event_id, name, venue_name, venue_address, start_time, end_time,
    sale_start_time, sale_end_time, purchase_limit, status
) VALUES (
    @demo_event_id, '周末场', '城市体育馆', '示例路 100 号',
    DATE_ADD(NOW(), INTERVAL 30 DAY), DATE_ADD(DATE_ADD(NOW(), INTERVAL 30 DAY), INTERVAL 2 HOUR),
    DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 29 DAY), 2, 'PUBLISHED'
);
SET @demo_session_id = LAST_INSERT_ID();

INSERT INTO t_ticket_tier (session_id, name, price, enabled)
VALUES (@demo_session_id, '普通票', 199.00, 1),
       (@demo_session_id, 'VIP 票', 399.00, 1);

COMMIT;
SELECT @demo_event_id AS event_id, @demo_session_id AS session_id;
