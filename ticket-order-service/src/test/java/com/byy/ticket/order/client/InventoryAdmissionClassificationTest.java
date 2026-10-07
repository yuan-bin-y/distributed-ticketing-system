package com.byy.ticket.order.client;

import com.byy.ticket.order.client.exception.InventoryServiceCallException;
import com.byy.ticket.order.config.InventoryClientProperties;
import com.byy.ticket.security.service.OrderInventoryCredential;
import com.byy.ticket.security.service.OrderInventoryCredentialProperties;
import com.byy.ticket.resilience.HttpResilienceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;

class InventoryAdmissionClassificationTest {
    @Test void hotspotRejectionDoesNotOpenWholeServiceCircuit() throws Exception {
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        var client=new InventoryClient(builder,new InventoryClientProperties("inventory",null,null),
                new OrderInventoryCredential(new OrderInventoryCredentialProperties("0".repeat(64),null)),
                HttpResilienceProperties.defaults());
        String id="1".repeat(32),url="http://inventory/internal/stock-reservations/"+id;
        for(int i=0;i<30;i++)server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"STOCK_BUSY\",\"message\":\"busy\",\"data\":null}"));
        for(int i=0;i<30;i++) {
            var failure=assertThrows(InventoryServiceCallException.class,()->client.getReservation(id));
            assertFalse(failure.isCircuitBreakerFailure());
        }
        assertEquals(CircuitBreaker.State.CLOSED,client.protection().circuitBreaker().getState());
        server.verify();
    }
    @Test void actualServiceFailureStillOpensCircuit() throws Exception {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var client=new InventoryClient(builder,new InventoryClientProperties("inventory",null,null),
                new OrderInventoryCredential(new OrderInventoryCredentialProperties("0".repeat(64),null)),
                HttpResilienceProperties.defaults());
        String id="1".repeat(32);
        for(int i=0;i<10;i++)server.expect(requestTo("http://inventory/internal/stock-reservations/"+id))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        for(int i=0;i<10;i++)assertThrows(InventoryServiceCallException.class,()->client.getReservation(id));
        assertEquals(CircuitBreaker.State.OPEN,client.protection().circuitBreaker().getState());server.verify();
    }
}
