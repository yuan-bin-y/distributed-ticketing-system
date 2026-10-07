package com.byy.ticket.gateway.web;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class OrderReadRetryFilterTest {
    private final ReactiveDiscoveryClient discovery=new ReactiveDiscoveryClient() {
        public String description(){return "test";}
        public Flux<String> getServices(){return Flux.just("ticket-order-service");}
        public Flux<ServiceInstance> getInstances(String id){return Flux.just(
                new DefaultServiceInstance("a",id,"dead",8062,false),
                new DefaultServiceInstance("b",id,"live",8062,false));}
    };
    private OrderReadRetryFilter filter(){return new OrderReadRetryFilter(discovery,true,2,Duration.ofSeconds(1),Duration.ofSeconds(3));}
    private MockServerWebExchange exchange(boolean post) {
        var request=post?MockServerHttpRequest.post("/api/orders"):MockServerHttpRequest.get("/api/orders/abc?q=a%20b");
        var exchange=MockServerWebExchange.from(request);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("ticket-order-service").uri("lb://ticket-order-service").predicate(e->true).build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR,URI.create("http://dead:8062/api/orders/abc?q=a%20b"));
        return exchange;
    }
    @Test void skipsFailedAddressAndPreservesEncodedQuery() {
        var exchange=exchange(false); List<URI> targets=new ArrayList<>();
        filter().filter(exchange,e->{URI target=e.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);targets.add(target);
            return "dead".equals(target.getHost())?Mono.error(new IOException("refused")):Mono.empty();}).block();
        assertEquals(List.of(URI.create("http://dead:8062/api/orders/abc?q=a%20b"),URI.create("http://live:8062/api/orders/abc?q=a%20b")),targets);
    }
    @Test void postIsNeverRetried() {
        int[] calls={0}; assertThrows(IOException.class,()->{
            try {filter().filter(exchange(true),e->{calls[0]++;return Mono.error(new IOException());}).block();}
            catch(RuntimeException error){throw (IOException)error.getCause();}
        }); assertEquals(1,calls[0]);
    }
    @Test void businessErrorStatusIsNotRetried() {
        int[] calls={0}; filter().filter(exchange(false),e->{calls[0]++;e.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);return Mono.empty();}).block();
        assertEquals(1,calls[0]);
    }
    @Test void neverRepeatsFailedEndpoints() {
        int[] calls={0}; var error=assertThrows(ResponseStatusException.class,()->filter().filter(exchange(false),e->{calls[0]++;return Mono.error(new IOException());}).block());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.getStatusCode());assertEquals(2,calls[0]);
    }
    @Test void totalBudgetCancelsHungAttempt() {
        var filter=new OrderReadRetryFilter(discovery,true,2,Duration.ofSeconds(1),Duration.ofMillis(50));
        var error=assertThrows(ResponseStatusException.class,()->filter.filter(exchange(false),e->Mono.never()).block());
        assertEquals(HttpStatus.GATEWAY_TIMEOUT,error.getStatusCode());
    }
}
