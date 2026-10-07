-- 创建请求及首次付款接收时保存上下文，后台履约可以跨进程重启继续关联。
ALTER TABLE t_order
    ADD COLUMN trace_parent VARCHAR(55) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN trace_state VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL;
