-- 与付款事实同事务保存原请求上下文；旧事件无法恢复父 Span，允许为空。
ALTER TABLE t_outbox_event
    ADD COLUMN trace_parent VARCHAR(55) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN trace_state VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL;
