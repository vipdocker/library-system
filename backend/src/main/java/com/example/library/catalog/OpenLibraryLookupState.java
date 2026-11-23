package com.example.library.catalog;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import com.example.library.catalog.dto.OpenLibraryLookupResponse;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;

/**
 * Process-local lookup state (OL-OPT-v1): bounded TTL caches and a single-flight coordinator.
 * <p>
 * Single flight: exactly one dedicated JDK worker thread with no task queue. The same ISBN + proxy revision merges at most
 * {@value #MAX_SUBSCRIBERS} subscribers; anything else is BUSY. The deadline is fixed by the first subscriber; late joiners
 * never extend it. Each subscriber waits and cancels independently; only when the last one leaves is the worker
 * interrupted. Nothing here touches the database.
 */
final class OpenLibraryLookupState implements AutoCloseable {
    static final int MAX_SUBSCRIBERS = 8;

    record Key(String isbn, boolean proxyEnabled, long proxyRevision) { }

    interface Subscription {
        CompletableFuture<OpenLibraryLookupResponse> result();
        /** Idempotent; never affects other subscribers of the same flight. */
        void cancel();
        boolean merged();
    }

    private final LongSupplier ticker;
    private final long budget;
    private final long grace;
    private final ThreadPoolExecutor worker;
    // At most two cancellable timer tasks for the one active flight; no per-subscriber delayed task backlog.
    private final java.util.concurrent.ScheduledThreadPoolExecutor timer = new java.util.concurrent.ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "open-library-deadline"); thread.setDaemon(true); return thread;
    });
    private Flight current;
    private boolean closed;

    OpenLibraryLookupState(LongSupplier ticker, Duration budget, Duration grace) {
        this.ticker = ticker;
        this.budget = budget.toNanos();
        this.grace = grace.toNanos();
        timer.setRemoveOnCancelPolicy(true);
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "open-library-lookup");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    private final class Flight {
        final Key key;
        final long deadline;
        final CompletableFuture<OpenLibraryLookupResponse> shared = new CompletableFuture<>();
        int subscribers;
        boolean finished;
        /** Every subscriber left: the worker is interrupted and may still be cleaning up; nobody may join it. */
        boolean abandoned;
        boolean timedOut;
        Thread running;
        java.util.concurrent.ScheduledFuture<?> deadlineTask;
        java.util.concurrent.ScheduledFuture<?> waitTask;
        Flight(Key key, long deadline) { this.key = key; this.deadline = deadline; }
    }

    Subscription subscribe(Key key, Supplier<OpenLibraryLookupResponse> work) {
        Flight flight;
        boolean merged;
        synchronized (this) {
            if (closed) { throw unavailable("CANCELLED"); }
            long now = ticker.getAsLong();
            if (current != null && !current.finished) {
                if (current.abandoned || !current.key.equals(key) || current.subscribers >= MAX_SUBSCRIBERS || current.deadline - now <= 0) { throw busy(); }
                flight = current; merged = true;
            } else {
                flight = new Flight(key, now + budget);
                current = flight;
                try {
                    Flight started = flight;
                    worker.execute(() -> run(started, work));
                    flight.deadlineTask = timer.schedule(() -> deadline(started), Math.max(1, flight.deadline - ticker.getAsLong()), TimeUnit.NANOSECONDS);
                    flight.waitTask = timer.schedule(() -> started.shared.completeExceptionally(unavailable("TIMEOUT")),
                            Math.max(1, flight.deadline + grace - ticker.getAsLong()), TimeUnit.NANOSECONDS);
                } catch (RejectedExecutionException ex) {
                    current = null;
                    throw busy(); // the previous (cancelled) worker is still cleaning up: no queue
                }
                merged = false;
            }
            flight.subscribers++;
        }
        return new FlightSubscription(flight, merged);
    }

    private void run(Flight flight, Supplier<OpenLibraryLookupResponse> work) {
        try {
            synchronized (this) {
                flight.running = Thread.currentThread();
                if (closed || flight.abandoned || flight.deadline - ticker.getAsLong() <= 0) {
                    flight.shared.completeExceptionally(unavailable("CANCELLED")); return;
                }
            }
            flight.shared.complete(work.get());
        } catch (RuntimeException | Error failure) {
            flight.shared.completeExceptionally(flight.abandoned ? unavailable(flight.timedOut ? "TIMEOUT" : "CANCELLED") : failure);
        } finally {
            synchronized (this) {
                flight.finished = true;
                flight.running = null;
                if (flight.deadlineTask != null) { flight.deadlineTask.cancel(false); }
                if (flight.waitTask != null) { flight.waitTask.cancel(false); }
                if (!flight.shared.isDone()) { flight.shared.completeExceptionally(unavailable("CANCELLED")); }
                if (current == flight) { current = null; }
            }
        }
    }

    private final class FlightSubscription implements Subscription {
        private final Flight flight;
        private final boolean merged;
        private final CompletableFuture<OpenLibraryLookupResponse> own = new CompletableFuture<>();
        private boolean left;

        FlightSubscription(Flight flight, boolean merged) {
            this.flight = flight;
            this.merged = merged;
            own.whenComplete((value, failure) -> cancel());
            flight.shared.whenComplete((value, failure) -> {
                if (failure == null) { own.complete(value); }
                else { own.completeExceptionally(failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure); }
            });
            // The flight's waitTask uses the first deadline; joining never schedules a new timer or extends it.
        }

        @Override public CompletableFuture<OpenLibraryLookupResponse> result() { return own; }
        @Override public boolean merged() { return merged; }

        @Override public void cancel() {
            boolean interrupt;
            synchronized (OpenLibraryLookupState.this) {
                if (left) { return; }
                left = true;
                flight.subscribers--;
                interrupt = flight.subscribers == 0 && !flight.shared.isDone();
                if (interrupt) { flight.abandoned = true; }
            }
            own.cancel(false); // a late result can never be delivered to this subscriber
            if (interrupt) { interrupt(flight); }
        }
    }

    private synchronized void deadline(Flight flight) {
        flight.timedOut = true;
        interrupt(flight);
    }

    private synchronized void interrupt(Flight flight) {
        flight.abandoned = true;
        if (flight.running != null && !flight.finished) { flight.running.interrupt(); }
        // A not-yet-started task is still executed once, skips work and runs its finally cleanup; it cannot get stuck.
    }

    /** A cache hit: no worker, nothing to cancel. */
    static Subscription completed(OpenLibraryLookupResponse response) {
        var done = CompletableFuture.completedFuture(response);
        return new Subscription() {
            @Override public CompletableFuture<OpenLibraryLookupResponse> result() { return done; }
            @Override public void cancel() { }
            @Override public boolean merged() { return false; }
        };
    }

    static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException) {
            if (cause.getCause() == null) { break; }
            cause = cause.getCause();
        }
        if (cause instanceof CancellationException || cause instanceof TimeoutException) { return unavailable("CANCELLED"); }
        return cause;
    }

    @Override
    public void close() {
        Flight flight;
        synchronized (this) { closed = true; flight = current; }
        if (flight != null) {
            interrupt(flight);
            flight.shared.completeExceptionally(unavailable("CANCELLED"));
        }
        timer.shutdownNow();
        worker.shutdownNow();
        try { worker.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private static ApiException busy() {
        return OpenLibraryRequestPolicy.rateLimited(OpenLibraryRequestPolicy.SECOND, "BUSY");
    }

    private static ApiException unavailable(String reason) {
        return new ApiException(ErrorCode.OPEN_LIBRARY_UNAVAILABLE, ErrorCode.OPEN_LIBRARY_UNAVAILABLE.getDefaultMessage(),
                Map.of("reason", reason), List.of());
    }

    /**
     * Bounded, immutable-value TTL cache: LRU by access, entry-count and byte limits, fixed expiry from insertion
     * (hits never renew the TTL). Oversized values are simply not cached.
     */
    static final class TtlCache<K, V> {
        private record Entry<V>(V value, long weight, long storedAt) { }
        private final LongSupplier ticker;
        private final long ttl;
        private final int maxEntries;
        private final long maxBytes;
        private final long maxEntryBytes;
        private final ToLongFunction<V> weigher;
        private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
        private long bytes;

        TtlCache(LongSupplier ticker, long ttlNanos, int maxEntries, long maxBytes, long maxEntryBytes, ToLongFunction<V> weigher) {
            this.ticker = ticker; this.ttl = ttlNanos; this.maxEntries = maxEntries; this.maxBytes = maxBytes;
            this.maxEntryBytes = Math.min(maxEntryBytes, maxBytes); this.weigher = weigher;
        }

        synchronized V get(K key) {
            Entry<V> entry = entries.get(key);
            if (entry == null) { return null; }
            if (ticker.getAsLong() - entry.storedAt() >= ttl) { remove(key); return null; }
            return entry.value();
        }

        synchronized boolean put(K key, V value) {
            long weight = Math.max(0, weigher.applyAsLong(value));
            remove(key);
            if (weight > maxEntryBytes) { return false; }
            long now = ticker.getAsLong();
            entries.values().removeIf(entry -> {
                boolean expired = now - entry.storedAt() >= ttl;
                if (expired) { bytes -= entry.weight(); }
                return expired;
            });
            entries.put(key, new Entry<>(value, weight, now));
            bytes += weight;
            Iterator<Map.Entry<K, Entry<V>>> eldest = entries.entrySet().iterator();
            while ((entries.size() > maxEntries || bytes > maxBytes) && eldest.hasNext()) {
                var next = eldest.next();
                if (next.getKey().equals(key)) { continue; }
                bytes -= next.getValue().weight();
                eldest.remove();
            }
            return true;
        }

        private void remove(K key) {
            Entry<V> old = entries.remove(key);
            if (old != null) { bytes -= old.weight(); }
        }

        synchronized void clear() { entries.clear(); bytes = 0; }
        synchronized int size() { return entries.size(); }
        synchronized long bytes() { return bytes; }
    }
}
