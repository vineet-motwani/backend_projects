package com.resume.gateflux;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class UserContextFilterTest {

    private UserContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new UserContextFilter();
    }

    @Test
    void enrichesDownstreamRequestWithUserAndAuthorities() {
        Jwt jwt = new Jwt(
                "mock-token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", "auth0|user_42", "permissions", List.of("read:users", "write:users"))
        );

        JwtAuthenticationToken auth = new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("read:users"), new SimpleGrantedAuthority("write:users")),
                "auth0|user_42"
        );

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/get").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> userIdHeader = new AtomicReference<>();
        AtomicReference<String> userRolesHeader = new AtomicReference<>();

        GatewayFilterChain chain = mutatedExchange -> {
            userIdHeader.set(mutatedExchange.getRequest().getHeaders().getFirst(UserContextFilter.USER_ID_HEADER));
            userRolesHeader.set(mutatedExchange.getRequest().getHeaders().getFirst(UserContextFilter.USER_ROLES_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl(auth))))
                .block();

        assertEquals("auth0|user_42", userIdHeader.get());
        assertEquals("read:users,write:users", userRolesHeader.get());
    }

    @Test
    void enrichesDownstreamRequestWithScopeFallbackWhenAuthoritiesEmpty() {
        Jwt jwt = new Jwt(
                "mock-token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", "client_svc_99", "scope", "read:users write:orders")
        );

        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, List.of(), "client_svc_99");

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/get").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> userIdHeader = new AtomicReference<>();
        AtomicReference<String> userRolesHeader = new AtomicReference<>();

        GatewayFilterChain chain = mutatedExchange -> {
            userIdHeader.set(mutatedExchange.getRequest().getHeaders().getFirst(UserContextFilter.USER_ID_HEADER));
            userRolesHeader.set(mutatedExchange.getRequest().getHeaders().getFirst(UserContextFilter.USER_ROLES_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl(auth))))
                .block();

        assertEquals("client_svc_99", userIdHeader.get());
        assertEquals("read:users write:orders", userRolesHeader.get());
    }

    @Test
    void passesUnauthenticatedRequestWithoutInjectingHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/actuator/health").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> userIdHeader = new AtomicReference<>();
        AtomicReference<String> userRolesHeader = new AtomicReference<>();

        GatewayFilterChain chain = mutatedExchange -> {
            userIdHeader.set(mutatedExchange.getRequest().getHeaders().getFirst(UserContextFilter.USER_ID_HEADER));
            userRolesHeader.set(mutatedExchange.getRequest().getHeaders().getFirst(UserContextFilter.USER_ROLES_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertNull(userIdHeader.get(), "Unauthenticated request should not have X-User-Id");
        assertNull(userRolesHeader.get(), "Unauthenticated request should not have X-User-Roles");
    }
}
