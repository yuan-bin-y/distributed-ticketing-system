package com.byy.ticket.observability;

import io.micrometer.tracing.*;
import io.micrometer.tracing.propagation.Propagator;
import java.util.*;

/** 只负责跨数据库/消息边界的 W3C 上下文；不参与业务幂等、租约和事务判定。 */
public final class TraceSupport {
    private final Tracer tracer;
    private final Propagator propagator;
    public TraceSupport(Tracer tracer, Propagator propagator) {
        this.tracer = tracer; this.propagator = propagator;
    }
    /** 保存当前 Span，而非只保存 traceId；无 Span 的历史数据允许为空。 */
    public Snapshot capture() {
        var span = tracer.currentSpan();
        if (span == null) return new Snapshot(null, null);
        var headers = new HashMap<String,String>();
        propagator.inject(span.context(), headers, Map::put);
        return new Snapshot(headers.get("traceparent"), headers.get("tracestate"));
    }
    /** 创建每次发送/消费/恢复的独立 Span；同一事件重试不会复用 Span ID。 */
    public Scope resume(Snapshot saved, String name, Span.Kind kind) {
        var carrier = saved.headers();
        var builder = carrier.isEmpty() ? tracer.spanBuilder().setNoParent()
                : propagator.extract(carrier, Map::get);
        builder.name(name);
        if (kind != null) builder.kind(kind);
        Span span = builder.start();
        return new Scope(span, tracer.withSpan(span));
    }
    /** 注入当前 Span 的上下文到新消息头，仅携带标准字段。 */
    public void inject(Map<String,Object> headers) { headers.putAll(capture().headers()); }
    public record Snapshot(String traceParent, String traceState) {
        public Map<String,String> headers() {
            // 校验大小和固定 W3C v00 格式，丢弃零 ID、恶意头与历史损坏数据。
            if (traceParent == null || !traceParent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                    || traceParent.substring(3,35).equals("0".repeat(32))
                    || traceParent.substring(36,52).equals("0".repeat(16))) return Map.of();
            var result = new HashMap<String,String>(); result.put("traceparent", traceParent);
            if (traceState != null && traceState.length() <= 512
                    && !traceState.contains("\r") && !traceState.contains("\n")) result.put("tracestate", traceState);
            return result;
        }
        public static Snapshot from(Map<String,Object> headers) {
            Object parent = headers.get("traceparent"), state = headers.get("tracestate");
            return new Snapshot(parent instanceof String ? (String) parent : null,
                    state instanceof String ? (String) state : null);
        }
    }
    /** 将当前 Span 放入线程作用域；关闭时先恢复外层上下文，再结束本次 Span。 */
    public static final class Scope implements AutoCloseable {
        private final Span span;
        private final Tracer.SpanInScope scope;
        Scope(Span span, Tracer.SpanInScope scope) { this.span = span; this.scope = scope; }
        public void error(Throwable failure) { span.error(failure); }
        public void tag(String key, String value) { span.tag(key, value); }
        @Override public void close() { try { scope.close(); } finally { span.end(); } }
    }
}
