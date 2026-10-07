package com.byy.ticket.observability;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName="org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration")
public class TraceSupportAutoConfiguration {
    @Bean TraceSupport traceSupport(Tracer tracer, Propagator propagator) { return new TraceSupport(tracer, propagator); }
}
