import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public final class Server {
    public static void main(String[] args) throws IOException {
        ReservationStore store = new ReservationStore(Clock.systemUTC());
        store.stock("demo", 10);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
        server.createContext("/inventory", e -> {
            if (!e.getRequestMethod().equals("GET")) { respond(e, 405, "method not allowed"); return; }
            String item = query(e).getOrDefault("item", "demo");
            respond(e, 200, "available=" + store.available(item));
        });
        server.createContext("/holds", e -> {
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
        });
        server.start();
        System.out.println("Listening on http://127.0.0.1:8080");
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
        e.sendResponseHeaders(status, bytes.length);
        try (var output = e.getResponseBody()) { output.write(bytes); }
    }
}
