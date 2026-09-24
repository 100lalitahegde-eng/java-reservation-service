import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

public final class Server {
    public static void main(String[] args) throws IOException, SQLException, GeneralSecurityException {
        String url = System.getenv("DATABASE_URL");
        if (url == null || url.isBlank()) throw new IllegalStateException("DATABASE_URL is required");
        String host = System.getenv().getOrDefault("LISTEN_HOST", "127.0.0.1");
        String keystore = System.getenv("TLS_KEYSTORE_FILE");
        boolean tls = keystore != null && !keystore.isBlank();
        if (tls && (System.getenv("API_TOKEN_FILE") == null || System.getenv("DATABASE_PASSWORD_FILE") == null))
            throw new IllegalStateException("TLS mode requires API_TOKEN_FILE and DATABASE_PASSWORD_FILE");
        String token = secret("API_TOKEN_FILE", "API_TOKEN");
        DatabaseReservationStore store = new DatabaseReservationStore(url,
                System.getenv().getOrDefault("DATABASE_USER", "postgres"),
                secret("DATABASE_PASSWORD_FILE", "DATABASE_PASSWORD"));
        requireToken(token);
        store.stockIfAbsent("demo", 10);
        HttpServer server = tls
                ? createTls(store, token, host, 8080, Path.of(keystore),
                        requiredPath("TLS_KEYSTORE_PASSWORD_FILE"))
                : createPlain(store, token, host, 8080);
        server.start();
        System.out.println("Listening on " + (tls ? "https" : "http") + "://" + host + ":8080");
    }

    /** Package-visible factory allows a real loopback HTTP test on an ephemeral port. */
    static HttpServer create(DatabaseReservationStore store, String token, int port) throws IOException {
        return createPlain(store, token, "127.0.0.1", port);
    }

    static HttpServer createPlain(DatabaseReservationStore store, String token, String host, int port) throws IOException {
        if (!InetAddress.getByName(host).isLoopbackAddress())
            throw new IllegalStateException("Plain HTTP is restricted to loopback");
        return configure(HttpServer.create(new InetSocketAddress(host, port), 0), store, token);
    }

    static HttpServer createTls(DatabaseReservationStore store, String token, String host, int port,
                                Path keystore, Path passwordFile) throws IOException, GeneralSecurityException {
        char[] password = Files.readString(passwordFile, StandardCharsets.UTF_8).strip().toCharArray();
        try {
            if (password.length == 0) throw new IllegalStateException("TLS keystore password is empty");
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(keystore)) { keys.load(input, password); }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(managers.getKeyManagers(), null, null);
            HttpsServer server = HttpsServer.create(new InetSocketAddress(host, port), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(context));
            return configure(server, store, token);
        } finally { Arrays.fill(password, '\0'); }
    }

    private static Path requiredPath(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return Path.of(value);
    }

    private static String secret(String fileName, String inlineName) throws IOException {
        String file = System.getenv(fileName), inline = System.getenv(inlineName);
        if (file != null && inline != null) throw new IllegalStateException("Set only " + fileName + " or " + inlineName);
        if (file != null) return readSecretFile(Path.of(file));
        return inline == null ? "" : inline;
    }

    static String readSecretFile(Path path) throws IOException {
        String value = Files.readString(path, StandardCharsets.UTF_8).strip();
        if (value.isEmpty()) throw new IllegalStateException("secret file is empty");
        return value;
    }

    private static HttpServer configure(HttpServer server, DatabaseReservationStore store, String token) {
        requireToken(token);
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
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
