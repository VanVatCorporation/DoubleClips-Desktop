package com.vanvatcorporation.doubleclips.ui.components;

import javafx.application.Platform;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Hands the LATEST value posted from any thread to the FX thread: a transfer reports progress hundreds of times a
 * second, and queueing one runLater per report would flood the UI. While one update is waiting, newer values replace it.
 */
public final class FxCoalescer<T> {
    private final Consumer<T> apply;
    private final AtomicReference<T> latest = new AtomicReference<>();
    private final AtomicBoolean pending = new AtomicBoolean();

    public FxCoalescer(Consumer<T> apply) { this.apply = apply; }

    public void post(T value) {
        latest.set(value);
        if (pending.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                pending.set(false);
                T v = latest.getAndSet(null);
                if (v != null) apply.accept(v);
            });
        }
    }
}
