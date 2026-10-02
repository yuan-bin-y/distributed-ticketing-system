-- 可选：在目标 MySQL 实例上执行一次，提前创建活动服务的空库。
-- 默认连接地址已启用自动建库，有创建数据库权限时无需手动执行本脚本。
-- 业务表由活动服务启动时的 Flyway 迁移创建。
CREATE DATABASE IF NOT EXISTS ticket_event
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
