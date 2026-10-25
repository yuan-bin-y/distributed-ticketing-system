package com.byy.ticket.routing;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.*;

import java.time.Duration;
import java.util.List;

/** Nacos心跳不代表依赖可用；只选择业务端口readyz成功的实例，全部失败时返回空列表。 */
public final class ReadinessInstanceSupplier implements ServiceInstanceListSupplier {
    private final ServiceInstanceListSupplier delegate;
    private final Mono<List<ServiceInstance>> available;

    public ReadinessInstanceSupplier(
            ServiceInstanceListSupplier delegate, WebClient web, Duration ttl) {
        this.delegate = delegate;
        available =
                Mono.defer(
                                () ->
                                        delegate.get()
                                                .next()
                                                .defaultIfEmpty(List.of())
                                                .flatMap(
                                                        instances ->
                                                                Flux.fromIterable(instances)
                                                                        .flatMap(
                                                                                instance -> {
                                                                                    var uri =
                                                                                            UriComponentsBuilder
                                                                                                    .newInstance()
                                                                                                    .scheme(
                                                                                                            instance
                                                                                                                            .isSecure()
                                                                                                                    ? "https"
                                                                                                                    : "http")
                                                                                                    .host(
                                                                                                            instance
                                                                                                                    .getHost())
                                                                                                    .port(
                                                                                                            instance
                                                                                                                    .getPort())
                                                                                                    .path(
                                                                                                            "/readyz")
                                                                                                    .build()
                                                                                                    .toUri();
                                                                                    return web.get()
                                                                                            .uri(
                                                                                                    uri)
                                                                                            .exchangeToMono(
                                                                                                    response ->
                                                                                                            response.releaseBody()
                                                                                                                    .thenReturn(
                                                                                                                            response.statusCode()
                                                                                                                                    .is2xxSuccessful()))
                                                                                            .timeout(
                                                                                                    Duration
                                                                                                            .ofMillis(
                                                                                                                    800))
                                                                                            .onErrorReturn(
                                                                                                    false)
                                                                                            .filter(
                                                                                                    Boolean
                                                                                                            ::booleanValue)
                                                                                            .map(
                                                                                                    healthy ->
                                                                                                            instance);
                                                                                },
                                                                                8)
                                                                        .collectList())
                                                .onErrorReturn(List.of()))
                        .cache(ttl);
    }

    @Override
    public String getServiceId() {
        return delegate.getServiceId();
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return available.flux();
    }
}
