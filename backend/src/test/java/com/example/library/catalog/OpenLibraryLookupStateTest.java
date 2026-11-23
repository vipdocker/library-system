package com.example.library.catalog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.example.library.catalog.dto.OpenLibraryLookupResponse;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OpenLibraryLookupStateTest {
    private static final long H = TimeUnit.HOURS.toNanos(1);
    private static OpenLibraryLookupResponse response(String isbn) {
        return new OpenLibraryLookupResponse(isbn, false, "https://openlibrary.org/isbn/" + isbn,
                new OpenLibraryLookupResponse.Fields(null, null, null, null, null), null, List.of(), null);
    }
    private static OpenLibraryLookupState.Key key(String isbn) { return new OpenLibraryLookupState.Key(isbn, true, 1); }

    // ---- bounded TTL cache ----
    @Test
    void cacheExpiresAfterTtlAndHitsNeverRenewIt() {
        var time = new AtomicLong(-H); // negative monotonic values are fine
        var cache = new OpenLibraryLookupState.TtlCache<String, String>(time::get, 24 * H, 100, 1024, 1024, value -> value.length());
        assertThat(cache.put("a", "x")).isTrue();
        time.addAndGet(23 * H); assertThat(cache.get("a")).isEqualTo("x");
        time.addAndGet(H - 1); assertThat(cache.get("a")).isEqualTo("x");
        time.addAndGet(1); assertThat(cache.get("a")).isNull(); assertThat(cache.size()).isZero(); assertThat(cache.bytes()).isZero();
    }

    @Test
    void cacheEvictsLeastRecentlyUsedByCountAndBytesAndRejectsOversizedEntries() {
        var time = new AtomicLong();
        var cache = new OpenLibraryLookupState.TtlCache<String, byte[]>(time::get, H, 3, 10, 6, value -> value.length);
        cache.put("a", new byte[2]); cache.put("b", new byte[2]); cache.put("c", new byte[2]);
        assertThat(cache.get("a")).isNotNull(); // a becomes most recent
        cache.put("d", new byte[2]);           // count limit 3 evicts b (LRU)
        assertThat(cache.get("b")).isNull(); assertThat(cache.size()).isEqualTo(3);
        cache.put("e", new byte[6]);           // bytes limit 10 evicts c then a
        assertThat(cache.bytes()).isLessThanOrEqualTo(10); assertThat(cache.get("e")).isNotNull(); assertThat(cache.get("c")).isNull();
        assertThat(cache.put("huge", new byte[7])).isFalse(); assertThat(cache.get("huge")).isNull();
        cache.clear(); assertThat(cache.size()).isZero(); assertThat(cache.bytes()).isZero();
    }

    @Test
    void cacheReplacingAKeyKeepsByteAccountingExact() {
        var time = new AtomicLong();
        var cache = new OpenLibraryLookupState.TtlCache<String, byte[]>(time::get, H, 3, 10, 10, value -> value.length);
        cache.put("a", new byte[5]); cache.put("a", new byte[3]);
        assertThat(cache.bytes()).isEqualTo(3); assertThat(cache.size()).isEqualTo(1);
    }

    // ---- single flight ----
    @Test
    void sameKeyMergesAtMostEightSubscribersIntoOneWorkerAndOthersAreBusy() throws Exception {
        try (var state = new OpenLibraryLookupState(System::nanoTime, Duration.ofSeconds(14), Duration.ofSeconds(1))) {
            var release = new CountDownLatch(1); var calls = new AtomicInteger();
            var subscriptions = new ArrayList<OpenLibraryLookupState.Subscription>();
            for (int i = 0; i < 8; i++) {
                subscriptions.add(state.subscribe(key("080442957X"), () -> { calls.incrementAndGet(); await(release); return response("080442957X"); }));
            }
            assertThat(subscriptions.subList(1, 8)).allMatch(OpenLibraryLookupState.Subscription::merged);
            assertThatThrownBy(() -> state.subscribe(key("080442957X"), () -> response("080442957X")))
                    .isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.getCode()).isEqualTo(ErrorCode.OPEN_LIBRARY_RATE_LIMITED); assertThat(e.getDetails()).containsEntry("reason", "BUSY"); });
            assertThatThrownBy(() -> state.subscribe(key("9780306406157"), () -> response("9780306406157")))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails()).containsEntry("reason", "BUSY"));
            assertThatThrownBy(() -> state.subscribe(new OpenLibraryLookupState.Key("080442957X", true, 2), () -> response("080442957X")))
                    .isInstanceOf(ApiException.class);
            release.countDown();
            for (var subscription : subscriptions) { assertThat(subscription.result().get(2, TimeUnit.SECONDS).isbn()).isEqualTo("080442957X"); }
            assertThat(calls.get()).isEqualTo(1);
            assertThat(state.subscribe(key("9780306406157"), () -> response("9780306406157")).result().get(2, TimeUnit.SECONDS).isbn()).isEqualTo("9780306406157");
        }
    }

    @Test
    void cancellingOneSubscriberNeverAffectsOthersAndLastCancelInterruptsTheWorker() throws Exception {
        try (var state = new OpenLibraryLookupState(System::nanoTime, Duration.ofSeconds(14), Duration.ofSeconds(1))) {
            var release = new CountDownLatch(1); var started = new CountDownLatch(1);
            var first = state.subscribe(key("080442957X"), () -> { started.countDown(); await(release); return response("080442957X"); });
            var second = state.subscribe(key("080442957X"), () -> response("never"));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            first.cancel(); first.cancel();
            assertThat(first.result()).isCancelled();
            release.countDown();
            assertThat(second.result().get(2, TimeUnit.SECONDS).isbn()).isEqualTo("080442957X");

            var interrupted = new CountDownLatch(1); var running = new CountDownLatch(1);
            var only = state.subscribe(key("9780306406157"), () -> {
                running.countDown();
                try { Thread.sleep(10_000); } catch (InterruptedException ex) { interrupted.countDown(); throw new IllegalStateException("cancelled"); }
                return response("late");
            });
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            only.cancel();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(only.result()).isCancelled();
        }
    }

    @Test
    void lateJoinerNeverExtendsTheFirstDeadlineAndTimesOutIndependently() throws Exception {
        try (var state = new OpenLibraryLookupState(System::nanoTime, Duration.ofMillis(400), Duration.ofMillis(100))) {
            var release = new CountDownLatch(1);
            long start = System.nanoTime();
            var first = state.subscribe(key("080442957X"), () -> { await(release); return response("080442957X"); });
            Thread.sleep(250);
            var late = state.subscribe(key("080442957X"), () -> response("never"));
            assertThatThrownBy(() -> late.result().get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ApiException.class);
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            // OL-OPT-v1 deadline is 400 ms, not 400+grace; a worker which exits on interrupt returns TIMEOUT immediately.
            assertThat(waited).isBetween(350L, 600L); // never the late joiner's 250+400 deadline
            assertThatThrownBy(() -> first.result().get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
            release.countDown();
        }
    }

    @Test
    void noQueueWhileTheCancelledWorkerIsStillCleaningUpAndCloseRejectsNewWork() throws Exception {
        var state = new OpenLibraryLookupState(System::nanoTime, Duration.ofSeconds(14), Duration.ofSeconds(1));
        var cleanup = new CountDownLatch(1); var running = new CountDownLatch(1); var exited = new AtomicBoolean();
        var only = state.subscribe(key("080442957X"), () -> {
            running.countDown();
            try { Thread.sleep(10_000); } catch (InterruptedException ex) { await(cleanup); exited.set(true); }
            return response("late");
        });
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
        only.cancel();
        assertThatThrownBy(() -> state.subscribe(key("080442957X"), () -> response("x")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails()).containsEntry("reason", "BUSY"));
        cleanup.countDown();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!exited.get() && System.nanoTime() < until) { Thread.sleep(10); }
        Thread.sleep(50);
        assertThat(state.subscribe(key("080442957X"), () -> response("ok")).result().get(2, TimeUnit.SECONDS).isbn()).isEqualTo("ok");
        state.close();
        assertThatThrownBy(() -> state.subscribe(key("080442957X"), () -> response("x")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.OPEN_LIBRARY_UNAVAILABLE));
    }

    @Test
    void workerIsInterruptedAtFirstDeadlineBeforeSubscriberGraceExpires() throws Exception {
        try (var state = new OpenLibraryLookupState(System::nanoTime, Duration.ofMillis(150), Duration.ofMillis(800))) {
            var started = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
            var subscription = state.subscribe(key("080442957X"), () -> {
                started.countDown();
                try { Thread.sleep(10_000); } catch (InterruptedException ex) { interrupted.countDown(); }
                return response("partial");
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(subscription.result().get(1, TimeUnit.SECONDS).isbn()).isEqualTo("partial");
        }
    }

    @Test
    void immediateCancelCannotLeaveAnAbandonedFlightStuckForever() throws Exception {
        try (var state = new OpenLibraryLookupState(System::nanoTime, Duration.ofSeconds(14), Duration.ofSeconds(1))) {
            var releaseStart = new CountDownLatch(1);
            var pool = (java.util.concurrent.ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils.getField(state, "worker");
            pool.setThreadFactory(runnable -> new Thread(() -> { await(releaseStart); runnable.run(); }, "test-delayed-worker"));
            var subscription = state.subscribe(key("080442957X"), () -> response("ok"));
            subscription.cancel(); // deterministic: cancel before the worker starts running the lookup task
            releaseStart.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            boolean recovered = false;
            while (System.nanoTime() < until) {
                try {
                    var next = state.subscribe(key("080442957X"), () -> response("ok"));
                    assertThat(next.result().get(1, TimeUnit.SECONDS).isbn()).isEqualTo("ok");
                    recovered = true; break;
                } catch (ApiException busy) { Thread.yield(); }
            }
            assertThat(recovered).isTrue();
        }
    }

    @Test
    void completedSubscriptionNeedsNoWorker() throws Exception {
        var done = OpenLibraryLookupState.completed(response("080442957X"));
        assertThat(done.result().get()).isNotNull(); done.cancel();
        assertThat(CompletableFuture.completedFuture(1)).isDone();
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(10, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted"); }
    }
}
