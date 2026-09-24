import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Real TLS handshake with a test certificate trusted explicitly by the test client. */
public final class ServerTlsTest {
    public static void main(String[] args) throws Exception {
        String url = require("DATABASE_URL");
        var store = new DatabaseReservationStore(url, System.getenv().getOrDefault("DATABASE_USER", "postgres"),
                System.getenv().getOrDefault("DATABASE_PASSWORD", ""));
        String item = "tls-" + UUID.randomUUID();
        store.stockIfAbsent(item, 1);
        Path tokenFile = Files.createTempFile("reservation-test-token", ".txt");
        try {
            Files.writeString(tokenFile, "tls-test-token-0123456789-0123456789\n");
            String token = Server.readSecretFile(tokenFile);
            try {
                Server.createPlain(store, token, "0.0.0.0", 0);
                throw new AssertionError("plain HTTP bound to the network");
            } catch (IllegalStateException expected) { /* intentionally rejected */ }

            Path keyStore = Path.of(require("TLS_TEST_KEYSTORE"));
            Path passwordFile = Path.of(require("TLS_TEST_PASSWORD_FILE"));
            HttpServer server = Server.createTls(store, token, "127.0.0.1", 0, keyStore, passwordFile);
            server.start();
            try {
                KeyStore trust = KeyStore.getInstance("PKCS12");
                char[] password = Server.readSecretFile(passwordFile).toCharArray();
                try (var input = Files.newInputStream(Path.of(require("TLS_TEST_TRUSTSTORE")))) {
                    trust.load(input, password);
                }
                TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                managers.init(trust);
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, managers.getTrustManagers(), null);
                HttpClient client = HttpClient.newBuilder().sslContext(context).build();
                URI uri = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/inventory?item=" + item);
                var denied = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
                check(denied.statusCode() == 401, "TLS request without token rejected");
                var accepted = client.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                check(accepted.statusCode() == 200 && accepted.body().equals("available=1"), "trusted TLS request accepted");
                check(!accepted.body().contains(token), "secret not echoed");
                System.out.println("TLS integration tests passed");
            } finally { server.stop(0); }
        } finally { Files.deleteIfExists(tokenFile); }
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
