-- 多个支付实例通过租约领取通知；进程退出后可以重新领取，旧任务不能覆盖新任务。
ALTER TABLE t_payment
    ADD COLUMN notify_lease_token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN notify_lease_until DATETIME(3) NULL;
