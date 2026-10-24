-- 升级时停止全部 Order 实例，回填后再启动，避免旧实例绕过新额度。
CREATE TABLE t_user_event_quota (
    user_id BIGINT NOT NULL,event_id BIGINT NOT NULL,occupied_sessions INT NOT NULL DEFAULT 0,
    PRIMARY KEY(user_id,event_id),CHECK(occupied_sessions>=0)
) ENGINE=InnoDB;
CREATE TABLE t_user_event_session (
    user_id BIGINT NOT NULL,event_id BIGINT NOT NULL,session_id BIGINT NOT NULL,active_orders INT NOT NULL DEFAULT 0,
    PRIMARY KEY(user_id,event_id,session_id),CHECK(active_orders>=0)
) ENGINE=InnoDB;
INSERT INTO t_user_event_session(user_id,event_id,session_id,active_orders)
SELECT o.user_id,i.event_id,i.session_id,COUNT(*) FROM t_order o JOIN t_order_item i ON i.order_id=o.id
WHERE o.quota_status='HELD' GROUP BY o.user_id,i.event_id,i.session_id;
INSERT INTO t_user_event_quota(user_id,event_id,occupied_sessions)
SELECT user_id,event_id,COUNT(*) FROM t_user_event_session WHERE active_orders>0 GROUP BY user_id,event_id;
ALTER TABLE t_order_item ADD COLUMN rule_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN purchase_limit INT NOT NULL DEFAULT 2147483647,
    ADD COLUMN max_sessions INT NOT NULL DEFAULT 2147483647,
    ADD COLUMN max_per_order INT NOT NULL DEFAULT 2147483647;
