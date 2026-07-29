package com.github.hbq969.ai.pi.core.execution;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/** Cooperative cancellation signal shared by an Agent request and its child work. */
public final class CancellationToken {

    private final AtomicReference<String> reason = new AtomicReference<>();

    public boolean cancel(String cancellationReason) {
        return reason.compareAndSet(null,
                cancellationReason == null || cancellationReason.isBlank()
                        ? "cancelled" : cancellationReason);
    }

    public boolean isCancelled() {
        return reason.get() != null;
    }

    public String reason() {
        return reason.get();
    }

    public void throwIfCancelled() {
        String value = reason.get();
        if (value != null) {
            throw new CancellationException(value);
        }
    }
}
