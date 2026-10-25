package com.byy.ticket.gateway.web;

import com.byy.ticket.routing.ReadinessInstanceSupplier;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.*;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.*;
import static org.junit.jupiter.api.Assertions.*;

class ReadinessInstanceSupplierTest {
    @Test void dependencyFailureRemovesInstanceAndAllFailedNeverFallsBackToUnhealthy() {
        ServiceInstance a = new DefaultServiceInstance("a", "order", "a", 8062, false);
        ServiceInstance b = new DefaultServiceInstance("b", "order", "b", 8062, false);
        ServiceInstanceListSupplier discovery = new ServiceInstanceListSupplier() {
            public String getServiceId() { return "order"; }
            public Flux<List<ServiceInstance>> get() { return Flux.just(List.of(a,b)); }
        };
        var http = WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(
                request.url().getHost().equals("b") ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).build())).build();
        assertEquals(List.of(b), new ReadinessInstanceSupplier(discovery,http,Duration.ZERO).get().blockFirst());
        var down = WebClient.builder().exchangeFunction(request -> Mono.error(new IllegalStateException("offline"))).build();
        assertEquals(List.of(), new ReadinessInstanceSupplier(discovery,down,Duration.ZERO).get().blockFirst());
    }
}
