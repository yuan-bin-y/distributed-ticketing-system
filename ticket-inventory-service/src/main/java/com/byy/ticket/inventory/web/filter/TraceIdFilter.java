package com.byy.ticket.inventory.web.filter;

import com.byy.ticket.observability.web.ServletTraceFilter;

import io.micrometer.tracing.Tracer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/** HTTP Observation之后恢复统一traceId，认证存储故障返回503。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceIdFilter extends ServletTraceFilter {
    public TraceIdFilter(ObjectMapper json, ObjectProvider<Tracer> tracers) {
        super(json, tracers, failure -> false);
    }
}
