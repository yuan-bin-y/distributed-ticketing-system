package com.byy.ticket.gateway.web;

import java.net.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import static org.junit.jupiter.api.Assertions.*;

class ClientIpResolverTest {
    String resolve(String proxies,String peer,String header) {
        var request=MockServerHttpRequest.get("/api/events").remoteAddress(new InetSocketAddress(peer,1234));
        if(header!=null)request.header("X-Forwarded-For",header);
        return new ClientIpResolver(proxies).resolve(request.build());
    }
    @Test void ignoresSpoofedHeaderFromUntrustedPeer() {
        assertEquals("198.51.100.8",resolve("10.0.0.0/24","198.51.100.8","1.2.3.4"));
        assertEquals("10.0.0.1",resolve("","10.0.0.1","1.2.3.4"));
    }
    @Test void removesOnlyTrustedRightHandProxies() {
        assertEquals("198.51.100.8",resolve("10.0.0.0/24","10.0.0.2","1.2.3.4, 198.51.100.8, 10.0.0.1"));
    }
    @Test void singleTrustedProxyPreservesSeparateClients() {
        assertEquals("198.51.100.1",resolve("10.0.0.2/32","10.0.0.2","198.51.100.1"));
        assertEquals("198.51.100.2",resolve("10.0.0.2/32","10.0.0.2","198.51.100.2"));
    }
    @Test void malformedOrOversizedHeadersFallBackToPeer() {
        for(String value:new String[]{"invalid.example","999.2.3.4","1.2.3.4,","1.2.3.4:1234","x".repeat(2049),"1.2.3.4,".repeat(21)})
            assertEquals("10.0.0.2",resolve("10.0.0.2","10.0.0.2",value));
    }
    @Test void handlesIpv6AndUnknownRemote() throws Exception {
        assertEquals(InetAddress.getByName("2001:db8::10").getHostAddress(),resolve("fd00::/64","fd00::1","2001:db8::10"));
        assertEquals("unknown",new ClientIpResolver("").resolve(MockServerHttpRequest.get("/").build()));
    }
    @Test void rejectsAmbiguousRepeatedHeadersAndBroadTrust() {
        var request=MockServerHttpRequest.get("/").remoteAddress(new InetSocketAddress("10.0.0.2",1)).header("X-Forwarded-For","1.2.3.4","5.6.7.8").build();
        assertEquals("10.0.0.2",new ClientIpResolver("10.0.0.2").resolve(request));
        assertThrows(IllegalArgumentException.class,()->new ClientIpResolver("0.0.0.0/0"));
        assertThrows(IllegalArgumentException.class,()->new ClientIpResolver("proxy.example/24"));
    }
}
