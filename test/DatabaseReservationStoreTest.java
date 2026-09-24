import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Integration tests against a real PostgreSQL instance. No mocks of database locking. */
public final class DatabaseReservationStoreTest {
    private static final String URL = require("DATABASE_URL");
    private static final String USER = System.getenv().getOrDefault("DATABASE_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("DATABASE_PASSWORD", "");

    public static void main(String[] args) throws Exception {
        try (var c = DriverManager.getConnection(URL, USER, PASSWORD); Statement s = c.createStatement()) {
            for (String sql : Files.readString(Path.of("schema.sql")).split(";")) {
                if (!sql.isBlank()) s.execute(sql);
            }
        }
        contentionAndRestart();
        expiryAndReplay();
        conflictingKeysAcrossItems();
        System.out.println("PostgreSQL integration tests passed");
    }

    private static void contentionAndRestart() throws Exception {
        String item = "seat-" + UUID.randomUUID();
        var first = store();
        var second = store();
        first.stockIfAbsent(item, 1);
        int threads = 24;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var won = new AtomicInteger();
        var winner = new AtomicReference<ReservationStore.Hold>();
        var failure = new AtomicReference<Throwable>();
        var pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int id = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        var result = (id % 2 == 0 ? first : second).hold(item, 1, 60, "unique-" + UUID.randomUUID());
                        if (result.status() == 201) { won.incrementAndGet(); winner.set(result.hold()); }
                        else check(result.status() == 409, "unexpected contention response: " + result.status());
                    } catch (Throwable ex) { failure.compareAndSet(null, ex); }
                    finally { done.countDown(); }
                });
            }
            start.countDown(); done.await();
        } finally { pool.shutdownNow(); }
        if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
        check(won.get() == 1, "one seat must have one winner");
        check(store().available(item) == 0, "new store instance must observe used capacity");
        check(store().confirm(winner.get().id()).status() == 200, "confirmation after restart");
        check(store().available(item) == 0, "confirmed reservation survives restart");
    }

    private static void expiryAndReplay() throws Exception {
        String item = "expiry-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        var store = store();
        store.stockIfAbsent(item, 1);
        var created = store.hold(item, 1, 60, key);
        check(created.status() == 201, "create hold");
        var replay = store().hold(item, 1, 60, key);
        check(replay.status() == 200 && replay.hold().id().equals(created.hold().id()), "same key returns same hold");
        check(store().hold(item, 1, 59, key).status() == 409, "changed TTL must conflict");
        try (var c = DriverManager.getConnection(URL, USER, PASSWORD);
             var s = c.prepareStatement("UPDATE holds SET expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?")) {
            s.setString(1, created.hold().id()); s.executeUpdate();
        }
        check(store().available(item) == 1, "expired hold releases capacity");
        check(store().confirm(created.hold().id()).status() == 404, "expired hold cannot be confirmed");
        check(store().hold(item, 1, 60, key).status() == 200, "replay retains original expired result");
        check(store().hold(item, 1, 60, "new-" + UUID.randomUUID()).status() == 201,
                "new key can use released capacity");
    }

    private static void conflictingKeysAcrossItems() throws Exception {
        String a = "a-" + UUID.randomUUID(), b = "b-" + UUID.randomUUID();
        var store = store(); store.stockIfAbsent(a, 1); store.stockIfAbsent(b, 1);
        String key = "shared-" + UUID.randomUUID();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var left = pool.submit(() -> { start.await(); return store().hold(a, 1, 60, key); });
            var right = pool.submit(() -> { start.await(); return store().hold(b, 1, 60, key); });
            start.countDown();
            int x = left.get().status(), y = right.get().status();
            check((x == 201 && y == 409) || (x == 409 && y == 201), "one key creates one hold");
        } finally { pool.shutdownNow(); }
    }

    private static DatabaseReservationStore store() { return new DatabaseReservationStore(URL, USER, PASSWORD); }
    private static String require(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " required for integration tests");
        return value;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
