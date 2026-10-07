package com.resume.gateflux;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
    }

    @Test
    void generatesCorrelationIdWhenMissing() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/get").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamHeader.set(mutatedExchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER));
            return mutatedExchange.getResponse().setComplete();
        };

        filter.filter(exchange, chain).block();

        assertNotNull(downstreamHeader.get(), "Downstream request should have X-Correlation-Id");
        assertFalse(downstreamHeader.get().isBlank());

        String responseHeader = exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertEquals(downstreamHeader.get(), responseHeader, "Response header should match downstream request header");
    }

    @Test
    void preservesExistingCorrelationId() {
        String existingTraceId = "trace-custom-uuid-456";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/get")
                .header(CorrelationIdFilter.CORRELATION_ID_HEADER, existingTraceId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamHeader.set(mutatedExchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER));
            return mutatedExchange.getResponse().setComplete();
        };

        filter.filter(exchange, chain).block();

        assertEquals(existingTraceId, downstreamHeader.get(), "Existing correlation ID should be preserved");
        assertEquals(existingTraceId, exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER));
    }
}
