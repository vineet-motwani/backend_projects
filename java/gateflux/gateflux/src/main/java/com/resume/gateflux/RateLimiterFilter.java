package com.resume.gateflux;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Operation;
import com.aerospike.client.Record;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.WritePolicy;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class RateLimiterFilter implements GlobalFilter, Ordered {

    private final AerospikeClient aerospikeClient;
    private static final int MAX_REQUESTS = 5; // per minute

    public RateLimiterFilter(AerospikeClient aerospikeClient) {
        this.aerospikeClient = aerospikeClient;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Identification using IP
        String ipAddress = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();

        // Thread Offloading
        return Mono.fromCallable(() -> isAllowed(ipAddress))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(allowed -> {
                    if (!allowed) {
                        // Bucket is empty; block with HTTP 429.
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        return exchange.getResponse().setComplete();
                    }
                    // Tokens left. Forward request to downstream server.
                    return chain.filter(exchange);
                });
    }

    private boolean isAllowed(String ipAddress) {
        try {
            // Key - exact row in Aerospike (Namespace, SetName, PrimaryKey)
            Key key = new Key("test", "rate_limit", ipAddress);

            // Bin - kind of column. Add 1 to the "hits" column.
            Bin bin = new Bin("hits", 1);

            WritePolicy policy = new WritePolicy();
            policy.recordExistsAction = RecordExistsAction.UPDATE;

            // ATOMIC OPERATION
            Record record = aerospikeClient.operate(policy, key, Operation.add(bin), Operation.get("hits"));
            int currentHits = record.getInt("hits");

            // If very first request, set a 60-second self-destruct timer on the row
            if (currentHits == 1) {
                WritePolicy ttlPolicy = new WritePolicy();
                ttlPolicy.expiration = 60; // TTL in seconds
                aerospikeClient.touch(ttlPolicy, key);
            }
            return currentHits <= MAX_REQUESTS;
        } catch (Exception e) {
            // Fail-Open Principle
            return true;
        }
    }

    @Override
    public int getOrder() {
        return -1; // Execute immediately after the Auth Bouncer
    }
}