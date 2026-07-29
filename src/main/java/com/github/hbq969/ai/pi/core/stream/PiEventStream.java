package com.github.hbq969.ai.pi.core.stream;

import com.github.hbq969.ai.pi.core.api.Message;
import com.github.hbq969.ai.pi.core.execution.CancellationToken;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 异步事件流，支持回调消费 + 阻塞等待最终结果。
 *
 * @param <T> 事件类型
 * @param <R> 最终结果类型
 */
public class PiEventStream<T, R> {

    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> cancelHooks = new CopyOnWriteArrayList<>();
    private final CompletableFuture<R> resultFuture = new CompletableFuture<>();
    private final Predicate<T> isDone;
    private final Function<T, R> extractResult;
    private final CancellationToken cancellationToken = new CancellationToken();
    private volatile boolean done;

    public PiEventStream(Predicate<T> isDone, Function<T, R> extractResult) {
        this.isDone = isDone;
        this.extractResult = extractResult;
    }

    /**
     * 注册事件回调（线程安全，可在 emit 前或 emit 期间调用）。
     */
    public PiEventStream<T, R> onEvent(Consumer<T> listener) {
        listeners.add(listener);
        return this;
    }

    /** Register work that must be cancelled when this stream is cancelled. */
    public PiEventStream<T, R> onCancel(Runnable hook) {
        if (cancellationToken.isCancelled()) {
            hook.run();
        } else {
            cancelHooks.add(hook);
            if (cancellationToken.isCancelled() && cancelHooks.remove(hook)) {
                hook.run();
            }
        }
        return this;
    }

    public CancellationToken cancellationToken() {
        return cancellationToken;
    }

    /** Cancel the stream and propagate cancellation to registered child work. */
    public boolean cancel(String reason) {
        if (done) {
            return false;
        }
        if (!cancellationToken.cancel(reason)) {
            return false;
        }
        done = true;
        for (Runnable hook : cancelHooks) {
            try {
                hook.run();
            } catch (Exception ignored) {
                // Cancellation must continue even if a child cleanup hook fails.
            }
        }
        resultFuture.completeExceptionally(new CancellationException(cancellationToken.reason()));
        return true;
    }

    /**
     * Provider 端调用，推送事件。调用方在 IO 线程中执行。
     * 当事件满足 isDone 条件时，自动 complete resultFuture。
     */
    public void emit(T event) {
        if (done) {
            return;
        }
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception ignored) {
                // 回调异常不影响流
            }
        }
        if (isDone.test(event)) {
            done = true;
            resultFuture.complete(extractResult.apply(event));
        }
    }

    /**
     * Provider 端调用，异常终止流。
     */
    public void fail(Throwable e) {
        if (done) {
            return;
        }
        done = true;
        resultFuture.completeExceptionally(e);
    }

    /**
     * 阻塞等待最终结果。
     */
    public R waitForResult() throws InterruptedException, ExecutionException {
        return resultFuture.get();
    }

    /**
     * 带超时的阻塞等待。
     */
    public R waitForResult(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return resultFuture.get(timeout, unit);
    }

    /**
     * 转为 CompletableFuture（非阻塞）。
     */
    public CompletableFuture<R> toFuture() {
        return resultFuture;
    }

    /**
     * 是否已结束。
     */
    public boolean isDone() {
        return done;
    }

    // ============ 工厂方法 ============

    /**
     * 创建 StreamEvent 专用流。
     */
    public static PiEventStream<StreamEvent, Message.AssistantMessage> forStream() {
        return new PiEventStream<>(
                e -> e instanceof StreamEvent.Done || e instanceof StreamEvent.Error,
                e -> {
                    if (e instanceof StreamEvent.Done d) {
                        return d.message();
                    } else if (e instanceof StreamEvent.Error err) {
                        return err.partial();
                    }
                    throw new IllegalStateException("Unexpected terminal event: " + e);
                });
    }
}
