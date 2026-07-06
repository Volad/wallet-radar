package com.walletradar.ingestion.adapter;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * WebFlux request threads ({@code reactor-http-nio-*}) are non-blocking; {@link Mono#block()} throws there.
 * Offload the reactive wait to {@link Schedulers#boundedElastic()} and join via {@link java.util.concurrent.Future}
 * (not {@code Mono.block}) so the caller thread is not asked to block a Reactor non-blocking scheduler.
 */
public final class ReactorBlocking {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private ReactorBlocking() {
    }

    public static <T> T block(Mono<T> mono) {
        return block(mono, DEFAULT_TIMEOUT);
    }

    public static <T> T block(Mono<T> mono, Duration timeout) {
        Duration t = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (Schedulers.isInNonBlockingThread()) {
            try {
                return mono.subscribeOn(Schedulers.boundedElastic())
                        .toFuture()
                        .get(t.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for reactive call", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(cause);
            } catch (TimeoutException e) {
                throw new IllegalStateException("Reactive call timed out after " + t, e);
            }
        }
        return mono.block(t);
    }
}
