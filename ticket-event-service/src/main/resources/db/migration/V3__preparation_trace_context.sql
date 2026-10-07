-- 后台库存准备的因果上下文；老票档无上下文时另建 Trace，不伪造原父 Span。
ALTER TABLE t_ticket_tier
    ADD COLUMN trace_parent VARCHAR(55) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN trace_state VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL;
