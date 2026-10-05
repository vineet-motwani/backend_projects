package com.resume.gateflux;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Netty channel handler that guards against Slowloris attacks during the request header phase.
 * It enforces a strict timeout for receiving the complete HTTP request headers.
 * If the headers are not decoded within the configured timeout, the channel is closed.
 */
public class HeaderTimeoutHandler extends ChannelDuplexHandler {

    private static final Logger log = LoggerFactory.getLogger(HeaderTimeoutHandler.class);

    private final long timeoutMillis;
    private ScheduledFuture<?> timeoutFuture;
    private boolean awaitingHeaders = true;

    public HeaderTimeoutHandler(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isActive()) {
            scheduleTimeout(ctx);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        scheduleTimeout(ctx);
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            awaitingHeaders = false;
            cancelTimeout();
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof LastHttpContent) {
            promise.addListener(future -> {
                if (future.isSuccess() && ctx.channel().isActive()) {
                    awaitingHeaders = true;
                    scheduleTimeout(ctx);
                }
            });
        }
        super.write(ctx, msg, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelTimeout();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        cancelTimeout();
        super.handlerRemoved(ctx);
    }

    private void scheduleTimeout(ChannelHandlerContext ctx) {
        cancelTimeout();
        if (timeoutMillis > 0 && awaitingHeaders) {
            timeoutFuture = ctx.executor().schedule(() -> {
                if (ctx.channel().isActive() && awaitingHeaders) {
                    log.warn("Closing connection: Slowloris header timeout of {} ms exceeded", timeoutMillis);
                    ctx.close();
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelTimeout() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }
}
