import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

/** Exercises real HTTP requests against a loopback listener and real PostgreSQL. */
public final class ServerHttpTest {
    private static final String TOKEN = "local-test-token-0123456789-0123456789";

    public static void main(String[] args) throws Exception {
        String url = System.getenv("DATABASE_URL");
        if (url == null || url.isBlank()) throw new IllegalStateException("DATABASE_URL required");
        var store = new DatabaseReservationStore(url, System.getenv().getOrDefault("DATABASE_USER", "postgres"),
                System.getenv().getOrDefault("DATABASE_PASSWORD", ""));
        String item = "http-" + UUID.randomUUID();
        store.stockIfAbsent(item, 1);
        try {
            Server.create(store, "short", 0);
            throw new AssertionError("short token accepted");
        } catch (IllegalStateException expected) { /* fail closed before binding */ }
        HttpServer server = Server.create(store, TOKEN, 0);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            String inventory = base + "/inventory?item=" + item;
            check(send("GET", inventory, null, null).statusCode() == 401, "missing token rejected");
            check(send("GET", inventory, "Bearer wrong", null).statusCode() == 401, "wrong token rejected");
            check(send("GET", inventory, "Basic " + TOKEN, null).statusCode() == 401, "wrong scheme rejected");
            check(send("GET", inventory, "Bearer " + TOKEN, null).body().equals("available=1"), "authorised read");

            String holdUrl = base + "/holds?item=" + item + "&quantity=1&seconds=60";
            check(send("POST", holdUrl, null, "first-key").statusCode() == 401, "unauthorised write rejected");
            check(store.available(item) == 1, "unauthorised write had no side effect");
            String key = "http-key-" + UUID.randomUUID();
            var created = send("POST", holdUrl, "Bearer " + TOKEN, key);
            check(created.statusCode() == 201 && created.body().startsWith("held id="), "hold created over HTTP");
            String id = created.body().substring("held id=".length());
            var replay = send("POST", holdUrl, "Bearer " + TOKEN, key);
            check(replay.statusCode() == 200 && replay.body().endsWith("id=" + id), "idempotent HTTP replay");
            check(send("GET", inventory, "Bearer " + TOKEN, null).body().equals("available=0"), "hold consumes capacity");
            String confirm = base + "/holds/" + id + "/confirm";
            check(send("POST", confirm, null, null).statusCode() == 401, "confirmation requires token");
            check(send("POST", confirm, "Bearer " + TOKEN, null).statusCode() == 200, "confirmation over HTTP");
            check(send("GET", inventory, "Bearer " + TOKEN, null).body().equals("available=0"), "confirmed capacity durable");
            check(!created.body().contains(TOKEN), "token not echoed in response");
            System.out.println("HTTP integration tests passed");
        } finally { server.stop(0); }
    }

    private static HttpResponse<String> send(String method, String url, String authorisation, String key) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        if (authorisation != null) builder.header("Authorization", authorisation);
        if (key != null) builder.header("Idempotency-Key", key);
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
