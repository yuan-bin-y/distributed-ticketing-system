package com.byy.ticket.order.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.byy.ticket.order.client.exception.InventoryServiceCallException;
import com.byy.ticket.order.config.InventoryClientProperties;
import com.byy.ticket.security.service.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class InventoryCapabilityTest {
    @Test
    void matchingProtocolIsCachedAndCarriesServiceIdentity() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = client(builder, true);
        server.expect(requestTo("http://ticket-inventory-service/internal/inventory/capabilities"))
                .andExpect(header(OrderInventoryCredential.HEADER, "a".repeat(64)))
                .andRespond(withSuccess(body(true), MediaType.APPLICATION_JSON));
        client.requireCompatible();
        client.requireCompatible();
        server.verify();
    }

    @Test
    void eitherDirectionOfConfigurationDriftRejectsBeforeAcceptingNewOrders() throws Exception {
        for (boolean enabled : new boolean[] {true, false}) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(anything())
                    .andRespond(withSuccess(body(!enabled), MediaType.APPLICATION_JSON));
            assertTrue(
                    assertThrows(
                                    InventoryServiceCallException.class,
                                    () -> client(builder, enabled).requireCompatible())
                            .getMessage()
                            .contains("配置或协议不一致"));
        }
    }

    @Test
    void unavailableInventoryIsRetryableAndFailureIsNotCached() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withServerError());
        server.expect(anything()).andRespond(withSuccess(body(true), MediaType.APPLICATION_JSON));
        var client = client(builder, true);
        assertThrows(InventoryServiceCallException.class, client::requireCompatible);
        assertDoesNotThrow(client::requireCompatible);
        server.verify();
    }

    private StockAdmissionClient client(RestClient.Builder builder, boolean enabled)
            throws Exception {
        return new StockAdmissionClient(
                builder,
                new InventoryClientProperties(null, null, null),
                new OrderInventoryCredential(
                        new OrderInventoryCredentialProperties("a".repeat(64), null)),
                enabled);
    }

    private String body(boolean enabled) {
        return "{\"code\":\"OK\",\"data\":{\"protocolVersion\":1,\"stockAdmission\":"
                + enabled
                + ",\"selectedSeats\":"
                + enabled
                + "}}";
    }
}
