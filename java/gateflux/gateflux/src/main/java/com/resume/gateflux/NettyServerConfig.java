package com.resume.gateflux;

import io.netty.channel.ChannelPipeline;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.reactor.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.NettyPipeline;

import java.time.Duration;

/**
 * Configuration for the embedded Netty server to protect against Slowloris DoS
 * and slow HTTP attacks at the transport and connection layer.
 */
@Configuration
public class NettyServerConfig {

    @Value("${gateflux.protection.header-timeout:10s}")
    private Duration headerTimeout;

    @Value("${gateflux.protection.read-timeout:10s}")
    private Duration readTimeout;

    @Value("${gateflux.protection.request-timeout:30s}")
    private Duration requestTimeout;

    @Bean
    public NettyServerCustomizer slowlorisNettyServerCustomizer() {
        return httpServer -> httpServer
                .idleTimeout(headerTimeout)
                .readTimeout(readTimeout)
                .requestTimeout(requestTimeout)
                .doOnConnection(connection -> {
                    ChannelPipeline pipeline = connection.channel().pipeline();
                    HeaderTimeoutHandler handler = new HeaderTimeoutHandler(headerTimeout.toMillis());
                    if (pipeline.get(NettyPipeline.HttpCodec) != null) {
                        pipeline.addAfter(NettyPipeline.HttpCodec, "headerTimeoutHandler", handler);
                    } else {
                        pipeline.addFirst("headerTimeoutHandler", handler);
                    }
                });
    }
}
