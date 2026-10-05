package com.resume.gateflux;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

/**
 * WebFilter that enforces maximum request body size limits across the entire server.
 * Protects against memory exhaustion and slow body upload attacks,
 * covering both fixed Content-Length requests and chunked streaming requests.
 */
@Component
public class MaxBodySizeFilter implements WebFilter, Ordered {

    private final long maxBodyBytes;

    public MaxBodySizeFilter(@Value("${gateflux.protection.max-body-size:5MB}") DataSize maxBodySize) {
        this.maxBodyBytes = maxBodySize.toBytes();
    }

    public long getMaxBodyBytes() {
        return maxBodyBytes;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        HttpHeaders headers = request.getHeaders();
        long contentLength = headers.getContentLength();

        // 1. Immediate rejection for Content-Length exceeding max permissible limit
        if (contentLength > maxBodyBytes) {
            exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
            return exchange.getResponse().setComplete();
        }

        // 2. Stream-level byte counter for chunked or unstated content-length requests
        ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                AtomicLong bytesCount = new AtomicLong();
                return super.getBody().flatMap(buffer -> {
                    long current = bytesCount.addAndGet(buffer.readableByteCount());
                    if (current > maxBodyBytes) {
                        DataBufferUtils.release(buffer);
                        return Flux.error(new ResponseStatusException(
                                HttpStatus.PAYLOAD_TOO_LARGE,
                                "Request payload exceeded maximum allowed size of " + maxBodyBytes + " bytes"
                        ));
                    }
                    return Flux.just(buffer);
                });
            }
        };

        return chain.filter(exchange.mutate().request(decoratedRequest).build());
    }

    @Override
    public int getOrder() {
        // Run first before security, route matching, and rate limiting
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
