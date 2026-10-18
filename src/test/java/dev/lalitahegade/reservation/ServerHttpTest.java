package dev.lalitahegade.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Real HTTP requests against a loopback listener and PostgreSQL. */
class ServerHttpTest {
    private static final String TOKEN = "local-test-token-0123456789-0123456789";

    private static DatabaseReservationStore store;

    @BeforeAll
    static void schema() throws Exception {
        PostgresSupport.applySchema();
        store = PostgresSupport.store();
    }

    @AfterAll
    static void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void shortTokenIsRejectedBeforeTheSocketBinds() {
        assertThrows(IllegalStateException.class, () -> Server.create(store, "short", 0));
    }

    @Test
    void requestPoolIsBoundedAndStopsWithTheServer() throws Exception {
        ThreadPoolExecutor pool;
        try (Server server = Server.create(store, TOKEN, 0)) {
            pool = assertInstanceOf(ThreadPoolExecutor.class, server.executor());
            assertInstanceOf(ArrayBlockingQueue.class, pool.getQueue());
            assertTrue(pool.getMaximumPoolSize() >= 4);
            assertTrue(pool.getQueue().remainingCapacity() <= pool.getMaximumPoolSize() * 16);
            server.start();
        }
        assertTrue(pool.isShutdown());
    }

    @Test
    void authorisedHoldConfirmAndReplay() throws Exception {
        String item = "http-" + UUID.randomUUID();
        store.stockIfAbsent(item, 1);
        try (Server server = Server.create(store, TOKEN, 0)) {
            server.start();
            String base = "http://127.0.0.1:" + server.address().getPort();
            String inventory = base + "/inventory?item=" + item;
            assertEquals(401, send("GET", inventory, null, null).statusCode(), "missing token rejected");
            assertEquals(401, send("GET", inventory, "Bearer wrong", null).statusCode(), "wrong token rejected");
            assertEquals(401, send("GET", inventory, "Basic " + TOKEN, null).statusCode(), "wrong scheme rejected");
            assertEquals("available=1", send("GET", inventory, "Bearer " + TOKEN, null).body(), "authorised read");

            String holdUrl = base + "/holds?item=" + item + "&quantity=1&seconds=60";
            assertEquals(401, send("POST", holdUrl, null, "first-key").statusCode(), "unauthorised write rejected");
            assertEquals(1, store.available(item), "unauthorised write had no side effect");
            String key = "http-key-" + UUID.randomUUID();
            HttpResponse<String> created = send("POST", holdUrl, "Bearer " + TOKEN, key);
            assertEquals(201, created.statusCode());
            assertTrue(created.body().startsWith("held id="), "hold created over HTTP");
            String id = created.body().substring("held id=".length());
            HttpResponse<String> replay = send("POST", holdUrl, "Bearer " + TOKEN, key);
            assertEquals(200, replay.statusCode());
            assertTrue(replay.body().endsWith("id=" + id), "idempotent HTTP replay");
            assertEquals("available=0", send("GET", inventory, "Bearer " + TOKEN, null).body(),
                    "hold consumes capacity");
            String confirm = base + "/holds/" + id + "/confirm";
            assertEquals(401, send("POST", confirm, null, null).statusCode(), "confirmation requires token");
            assertEquals(200, send("POST", confirm, "Bearer " + TOKEN, null).statusCode(), "confirmation over HTTP");
            assertEquals("available=0", send("GET", inventory, "Bearer " + TOKEN, null).body(),
                    "confirmed capacity durable");
            assertFalse(created.body().contains(TOKEN), "token not echoed in response");
        }
    }

    private static HttpResponse<String> send(String method, String url, String authorisation, String key)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        if (authorisation != null) {
            builder.header("Authorization", authorisation);
        }
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
