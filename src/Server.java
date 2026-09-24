import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public final class Server {
    public static void main(String[] args) throws IOException, SQLException {
        String url = System.getenv("DATABASE_URL");
        if (url == null || url.isBlank()) throw new IllegalStateException("DATABASE_URL is required");
        String token = System.getenv("API_TOKEN");
        DatabaseReservationStore store = new DatabaseReservationStore(url,
                System.getenv().getOrDefault("DATABASE_USER", "postgres"),
                System.getenv().getOrDefault("DATABASE_PASSWORD", ""));
        requireToken(token);
        store.stockIfAbsent("demo", 10);
        HttpServer server = create(store, token, 8080);
        server.start();
        System.out.println("Listening on http://127.0.0.1:8080");
    }

    /** Package-visible factory allows a real loopback HTTP test on an ephemeral port. */
    static HttpServer create(DatabaseReservationStore store, String token, int port) throws IOException {
        requireToken(token);
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/inventory", e -> {
            if (!authorised(e, expected)) { reject(e); return; }
            if (!e.getRequestURI().getPath().equals("/inventory")) { respond(e, 404, "not found"); return; }
            if (!e.getRequestMethod().equals("GET")) { respond(e, 405, "method not allowed"); return; }
            String item = query(e).getOrDefault("item", "demo");
            try { respond(e, 200, "available=" + store.available(item)); }
            catch (SQLException ex) { respond(e, 503, "database unavailable"); }
        });
        server.createContext("/holds", e -> {
            if (!authorised(e, expected)) { reject(e); return; }
            try {
                if (e.getRequestMethod().equals("POST") && e.getRequestURI().getPath().equals("/holds")) {
                    Map<String, String> q = query(e);
                    var result = store.hold(q.get("item"), Integer.parseInt(q.getOrDefault("quantity", "0")),
                            Long.parseLong(q.getOrDefault("seconds", "0")), e.getRequestHeaders().getFirst("Idempotency-Key"));
                    respond(e, result.status(), result.message() + (result.hold() == null ? "" : " id=" + result.hold().id()));
                } else if (e.getRequestMethod().equals("POST") && e.getRequestURI().getPath().matches("/holds/[^/]+/confirm")) {
                    String id = e.getRequestURI().getPath().split("/")[2];
                    var result = store.confirm(id);
                    respond(e, result.status(), result.message());
                } else respond(e, 404, "not found");
            } catch (IllegalArgumentException ex) { respond(e, 400, "invalid request"); }
            catch (SQLException ex) { respond(e, 503, "database unavailable"); }
        });
        return server;
    }

    private static void requireToken(String token) {
        if (token == null || token.length() < 32 || token.isBlank())
            throw new IllegalStateException("API_TOKEN must contain at least 32 characters");
    }

    private static boolean authorised(HttpExchange e, byte[] expected) {
        String header = e.getRequestHeaders().getFirst("Authorization");
        return header != null && header.startsWith("Bearer ") &&
                MessageDigest.isEqual(expected, header.substring(7).getBytes(StandardCharsets.UTF_8));
    }

    private static void reject(HttpExchange e) throws IOException {
        e.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        respond(e, 401, "unauthorised");
    }

    private static Map<String, String> query(HttpExchange e) {
        String raw = e.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) return Map.of();
        return Arrays.stream(raw.split("&")).map(x -> x.split("=", 2)).filter(x -> x.length == 2)
                .collect(Collectors.toMap(x -> URLDecoder.decode(x[0], StandardCharsets.UTF_8),
                        x -> URLDecoder.decode(x[1], StandardCharsets.UTF_8), (a, b) -> b));
    }

    private static void respond(HttpExchange e, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        e.getResponseHeaders().set("Cache-Control", "no-store");
        e.sendResponseHeaders(status, bytes.length);
        try (var output = e.getResponseBody()) { output.write(bytes); }
    }
}
