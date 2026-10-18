package dev.lalitahegade.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DatabaseReservationStoreTest {
    @BeforeAll
    static void schema() throws Exception {
        PostgresSupport.applySchema();
    }

    @Test
    void oneWinnerSurvivesANewStoreInstance() throws Exception {
        String item = "seat-" + UUID.randomUUID();
        try (DatabaseReservationStore first = PostgresSupport.store();
                DatabaseReservationStore second = PostgresSupport.store()) {
            first.stockIfAbsent(item, 1);
            int threads = 24;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger won = new AtomicInteger();
            AtomicReference<ReservationStore.Hold> winner = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (int i = 0; i < threads; i++) {
                    int id = i;
                    pool.submit(() -> {
                        try {
                            start.await();
                            DatabaseReservationStore store = id % 2 == 0 ? first : second;
                            ReservationStore.Result result = store.hold(item, 1, 60, "unique-" + UUID.randomUUID());
                            if (result.status() == 201) {
                                won.incrementAndGet();
                                winner.set(result.hold());
                            } else if (result.status() != 409) {
                                failure.compareAndSet(null,
                                        new AssertionError("unexpected contention response: " + result.status()));
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
            assertEquals(1, won.get(), "one seat must have one winner");
            try (DatabaseReservationStore restarted = PostgresSupport.store()) {
                assertEquals(0, restarted.available(item), "new store instance must observe used capacity");
                assertEquals(200, restarted.confirm(winner.get().id()).status(), "confirmation after restart");
            }
            try (DatabaseReservationStore restarted = PostgresSupport.store()) {
                assertEquals(0, restarted.available(item), "confirmed reservation survives restart");
            }
        }
    }

    @Test
    void expiryReleasesCapacityAndReplayKeepsTheOriginalHold() throws Exception {
        String item = "expiry-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        try (DatabaseReservationStore store = PostgresSupport.store()) {
            store.stockIfAbsent(item, 1);
            ReservationStore.Result created = store.hold(item, 1, 60, key);
            assertEquals(201, created.status(), "create hold");
            ReservationStore.Result replay = store.hold(item, 1, 60, key);
            assertEquals(200, replay.status());
            assertEquals(created.hold().id(), replay.hold().id(), "same key returns same hold");
            assertEquals(409, store.hold(item, 1, 59, key).status(), "changed TTL must conflict");
            try (var connection = DriverManager.getConnection(
                            PostgresSupport.URL, PostgresSupport.USER, PostgresSupport.PASSWORD);
                    var statement = connection.prepareStatement(
                            "UPDATE holds SET expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?")) {
                statement.setString(1, created.hold().id());
                statement.executeUpdate();
            }
            assertEquals(1, store.available(item), "expired hold releases capacity");
            assertEquals(404, store.confirm(created.hold().id()).status(), "expired hold cannot be confirmed");
            assertEquals(200, store.hold(item, 1, 60, key).status(), "replay retains original expired result");
            assertEquals(201, store.hold(item, 1, 60, "new-" + UUID.randomUUID()).status(),
                    "new key can use released capacity");
        }
    }

    @Test
    void oneKeyCreatesASingleHoldAcrossItems() throws Exception {
        String firstItem = "a-" + UUID.randomUUID();
        String secondItem = "b-" + UUID.randomUUID();
        String key = "shared-" + UUID.randomUUID();
        try (DatabaseReservationStore leftStore = PostgresSupport.store();
                DatabaseReservationStore rightStore = PostgresSupport.store()) {
            leftStore.stockIfAbsent(firstItem, 1);
            leftStore.stockIfAbsent(secondItem, 1);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<ReservationStore.Result> left = pool.submit(() -> {
                    start.await();
                    return leftStore.hold(firstItem, 1, 60, key);
                });
                Future<ReservationStore.Result> right = pool.submit(() -> {
                    start.await();
                    return rightStore.hold(secondItem, 1, 60, key);
                });
                start.countDown();
                int leftStatus = left.get(30, TimeUnit.SECONDS).status();
                int rightStatus = right.get(30, TimeUnit.SECONDS).status();
                assertTrue((leftStatus == 201 && rightStatus == 409) || (leftStatus == 409 && rightStatus == 201),
                        "one key creates one hold");
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
