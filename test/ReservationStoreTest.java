import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReservationStoreTest {
    public static void main(String[] args) throws Exception {
        var store = new ReservationStore(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        store.stock("seat", 1);
        int threads = 40;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var won = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int id = i;
                pool.submit(() -> { try { start.await(); if (store.hold("seat", 1, 60, "request-" + id).status() == 201) won.incrementAndGet(); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); } finally { done.countDown(); } });
            }
            start.countDown(); done.await();
        } finally { pool.shutdownNow(); }
        check(won.get() == 1, "exactly one winner");
        check(store.available("seat") == 0, "capacity not oversold");
        var replay = store.hold("seat", 1, 60, "request-0");
        check(replay.status() == 200 || replay.status() == 409, "idempotency status");
        var expiring = new ReservationStore(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        expiring.stock("item", 2);
        var h = expiring.hold("item", 1, 60, "one");
        check(h.status() == 201 && expiring.confirm(h.hold().id()).status() == 200, "confirmation");
        check(expiring.available("item") == 1, "confirmed reservation consumes capacity");
        System.out.println("ReservationStore tests passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
