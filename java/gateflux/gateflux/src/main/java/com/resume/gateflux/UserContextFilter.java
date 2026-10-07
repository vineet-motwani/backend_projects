package com.resume.gateflux;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

/**
 * GlobalFilter that enriches downstream requests with authenticated user identity.
 * Extracts user context (subject and roles/scopes) from the validated JWT in the SecurityContext
 * and injects them as downstream headers (X-User-Id, X-User-Roles).
 * This offloads JWT parsing and validation from backend microservices.
 */
@Component
public class UserContextFilter implements GlobalFilter, Ordered {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .map(jwtAuth -> {
                    Jwt jwt = jwtAuth.getToken();
                    ServerHttpRequest.Builder reqBuilder = exchange.getRequest().mutate();

                    if (jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
                        reqBuilder.header(USER_ID_HEADER, jwt.getSubject());
                    }

                    String authorities = jwtAuth.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .filter(a -> a != null && !a.isBlank())
                            .collect(Collectors.joining(","));

                    if (!authorities.isBlank()) {
                        reqBuilder.header(USER_ROLES_HEADER, authorities);
                    } else {
                        String scope = jwt.getClaimAsString("scope");
                        if (scope != null && !scope.isBlank()) {
                            reqBuilder.header(USER_ROLES_HEADER, scope);
                        }
                    }

                    return exchange.mutate().request(reqBuilder.build()).build();
                })
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    @Override
    public int getOrder() {
        // Runs after security and RateLimiterFilter (-1), before downstream routing
        return 0;
    }
}
