package dev.lalitahegade.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReservationStoreTest {
    @Test
    void concurrentHoldsAdmitExactlyOneWinner() throws Exception {
        ReservationStore store = new ReservationStore(
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        store.stock("seat", 1);
        int threads = 40;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger won = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                int id = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        if (store.hold("seat", 1, 60, "request-" + id).status() == 201) {
                            won.incrementAndGet();
                        }
                    } catch (Throwable ex) {
                        failure.compareAndSet(null, ex);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "workers finished");
        } finally {
            pool.shutdownNow();
        }
        if (failure.get() != null) {
            throw new AssertionError("worker failed", failure.get());
        }
        assertEquals(1, won.get(), "exactly one winner");
        assertEquals(0, store.available("seat"), "capacity not oversold");
    }

    @Test
    void replayReturnsTheOriginalHold() {
        ReservationStore store = new ReservationStore(
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        store.stock("seat", 2);
        ReservationStore.Result created = store.hold("seat", 1, 60, "same-key");
        ReservationStore.Result replay = store.hold("seat", 1, 60, "same-key");
        assertEquals(201, created.status());
        assertEquals(200, replay.status());
        assertEquals(created.hold().id(), replay.hold().id());
        assertEquals(409, store.hold("seat", 2, 60, "same-key").status());
    }

    @Test
    void replayAfterExpiryReturnsTheOriginalHold() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        ReservationStore store = new ReservationStore(new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        });
        store.stock("seat", 1);
        ReservationStore.Result created = store.hold("seat", 1, 60, "late-key");
        assertEquals(201, created.status());
        now.set(now.get().plusSeconds(61));
        assertEquals(1, store.available("seat"), "expired hold releases capacity");
        assertEquals(404, store.confirm(created.hold().id()).status(), "expired hold cannot be confirmed");
        ReservationStore.Result replay = store.hold("seat", 1, 60, "late-key");
        assertEquals(200, replay.status());
        assertEquals(created.hold(), replay.hold(), "replay returns the original expired hold");
        assertEquals(409, store.hold("seat", 1, 120, "late-key").status(), "different TTL is a conflict");
        assertEquals(1, store.available("seat"), "replay does not take capacity");
    }

    @Test
    void confirmationConsumesCapacity() {
        ReservationStore store = new ReservationStore(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        store.stock("item", 2);
        ReservationStore.Result hold = store.hold("item", 1, 60, "one");
        assertEquals(201, hold.status());
        assertEquals(200, store.confirm(hold.hold().id()).status());
        assertEquals(1, store.available("item"), "confirmed reservation consumes capacity");
    }
}
