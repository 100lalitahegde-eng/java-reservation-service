package dev.lalitahegade.reservation;

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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

public final class Server implements AutoCloseable {
    private static final int MIN_THREADS = 4;
    private static final int QUEUE_PER_THREAD = 16;

    private final HttpServer httpServer;
    private final ExecutorService requestExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Server(HttpServer httpServer, ExecutorService requestExecutor) {
        this.httpServer = httpServer;
        this.requestExecutor = requestExecutor;
    }

    public static void main(String[] args) throws IOException, SQLException, GeneralSecurityException {
        String url = System.getenv("DATABASE_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is required");
        }
        String host = System.getenv().getOrDefault("LISTEN_HOST", "127.0.0.1");
        String keystore = System.getenv("TLS_KEYSTORE_FILE");
        boolean tls = keystore != null && !keystore.isBlank();
        if (tls) {
            validateTlsSecrets(System.getenv());
        }
        String token = secret("API_TOKEN_FILE", "API_TOKEN");
        requireToken(token);
        DatabaseReservationStore store = new DatabaseReservationStore(
                url,
                System.getenv().getOrDefault("DATABASE_USER", "postgres"),
                secret("DATABASE_PASSWORD_FILE", "DATABASE_PASSWORD"));
        Server server = null;
        try {
            store.stockIfAbsent("demo", 10);
            server = tls
                    ? createTls(store, token, host, 8080, Path.of(keystore), requiredPath("TLS_KEYSTORE_PASSWORD_FILE"))
                    : createPlain(store, token, host, 8080);
            server.start();
            System.out.println("Listening on " + (tls ? "https" : "http") + "://" + host + ":8080");
            Server running = server;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.close();
                store.close();
            }, "reservation-shutdown"));
        } catch (Throwable failure) {
            if (server != null) {
                server.close();
            }
            store.close();
            throw failure;
        }
    }

    /** Loopback HTTP listener on an ephemeral port when {@code port} is 0. */
    static Server create(DatabaseReservationStore store, String token, int port) throws IOException {
        return createPlain(store, token, "127.0.0.1", port);
    }

    static Server createPlain(DatabaseReservationStore store, String token, String host, int port) throws IOException {
        requireToken(token);
        if (!InetAddress.getByName(host).isLoopbackAddress()) {
            throw new IllegalStateException("Plain HTTP is restricted to loopback");
        }
        return configure(HttpServer.create(new InetSocketAddress(host, port), 0), store, token);
    }

    static Server createTls(DatabaseReservationStore store, String token, String host, int port,
                             Path keystore, Path passwordFile) throws IOException, GeneralSecurityException {
        requireToken(token);
        char[] password = readSecretFile(passwordFile).toCharArray();
        try {
            if (password.length == 0) {
                throw new IllegalStateException("TLS keystore password is empty");
            }
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(keystore)) {
                keys.load(input, password);
            }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(managers.getKeyManagers(), null, null);
            HttpsServer http = HttpsServer.create(new InetSocketAddress(host, port), 0);
            http.setHttpsConfigurator(new HttpsConfigurator(context));
            return configure(http, store, token);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    static void validateTlsSecrets(Map<String, String> env) {
        for (String name : new String[] {"API_TOKEN", "DATABASE_PASSWORD"}) {
            String file = env.get(name + "_FILE");
            if (file == null || file.isBlank()) {
                throw new IllegalStateException("TLS mode requires " + name + "_FILE");
            }
            if (env.containsKey(name)) {
                throw new IllegalStateException("Set only " + name + "_FILE or " + name);
            }
        }
    }

    static String readSecretFile(Path path) throws IOException {
        String value = Files.readString(path, StandardCharsets.UTF_8);
        if (value.endsWith("\r\n")) {
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("\n")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            throw new IllegalStateException("secret file is empty");
        }
        return value;
    }

    public void start() {
        httpServer.start();
    }

    public InetSocketAddress address() {
        return httpServer.getAddress();
    }

    ExecutorService executor() {
        return requestExecutor;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        httpServer.stop(0);
        requestExecutor.shutdown();
        try {
            if (!requestExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                requestExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            requestExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static Server configure(HttpServer http, DatabaseReservationStore store, String token) {
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        ExecutorService executor = newRequestExecutor();
        http.setExecutor(executor);
        http.createContext("/inventory", exchange -> inventory(exchange, store, expected));
        http.createContext("/holds", exchange -> holds(exchange, store, expected));
        return new Server(http, executor);
    }

    /**
     * Fixed thread count and a bounded queue. When both are full, the acceptor runs the
     * request, so a saturated process stops taking new connections at the same rate.
     */
    private static ExecutorService newRequestExecutor() {
        int threads = Math.max(MIN_THREADS, Runtime.getRuntime().availableProcessors());
        AtomicInteger ids = new AtomicInteger();
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(threads * QUEUE_PER_THREAD),
                runnable -> {
                    Thread thread = new Thread(runnable, "reservation-http-" + ids.incrementAndGet());
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private static void inventory(HttpExchange exchange, DatabaseReservationStore store, byte[] expected)
            throws IOException {
        if (!authorised(exchange, expected)) {
            reject(exchange);
            return;
        }
        if (!exchange.getRequestURI().getPath().equals("/inventory")) {
            respond(exchange, 404, "not found");
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 405, "method not allowed");
            return;
        }
        String item = query(exchange).getOrDefault("item", "demo");
        try {
            respond(exchange, 200, "available=" + store.available(item));
        } catch (SQLException ex) {
            respond(exchange, 503, "database unavailable");
        }
    }

    private static void holds(HttpExchange exchange, DatabaseReservationStore store, byte[] expected)
            throws IOException {
        if (!authorised(exchange, expected)) {
            reject(exchange);
            return;
        }
        try {
            if (exchange.getRequestMethod().equals("POST") && exchange.getRequestURI().getPath().equals("/holds")) {
                Map<String, String> parameters = query(exchange);
                ReservationStore.Result result = store.hold(
                        parameters.get("item"),
                        Integer.parseInt(parameters.getOrDefault("quantity", "0")),
                        Long.parseLong(parameters.getOrDefault("seconds", "0")),
                        exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                String body = result.message() + (result.hold() == null ? "" : " id=" + result.hold().id());
                respond(exchange, result.status(), body);
            } else if (exchange.getRequestMethod().equals("POST")
                    && exchange.getRequestURI().getPath().matches("/holds/[^/]+/confirm")) {
                String id = exchange.getRequestURI().getPath().split("/")[2];
                ReservationStore.Result result = store.confirm(id);
                respond(exchange, result.status(), result.message());
            } else {
                respond(exchange, 404, "not found");
            }
        } catch (IllegalArgumentException ex) {
            respond(exchange, 400, "invalid request");
        } catch (SQLException ex) {
            respond(exchange, 503, "database unavailable");
        }
    }

    private static Path requiredPath(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return Path.of(value);
    }

    private static String secret(String fileName, String inlineName) throws IOException {
        String file = System.getenv(fileName);
        String inline = System.getenv(inlineName);
        if (file != null && inline != null) {
            throw new IllegalStateException("Set only " + fileName + " or " + inlineName);
        }
        if (file != null) {
            return readSecretFile(Path.of(file));
        }
        return inline == null ? "" : inline;
    }

    private static void requireToken(String token) {
        if (token == null || token.length() < 32 || token.isBlank()) {
            throw new IllegalStateException("API_TOKEN must contain at least 32 characters");
        }
    }

    private static boolean authorised(HttpExchange exchange, byte[] expected) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null
                && header.startsWith("Bearer ")
                && MessageDigest.isEqual(expected, header.substring(7).getBytes(StandardCharsets.UTF_8));
    }

    private static void reject(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        respond(exchange, 401, "unauthorised");
    }

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return Arrays.stream(raw.split("&"))
                .map(part -> part.split("=", 2))
                .filter(part -> part.length == 2)
                .collect(Collectors.toMap(
                        part -> URLDecoder.decode(part[0], StandardCharsets.UTF_8),
                        part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8),
                        (left, right) -> right));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
