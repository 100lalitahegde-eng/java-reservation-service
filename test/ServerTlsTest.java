import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
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
        testConfiguration();
        Path tokenFile = Files.createTempFile("reservation-test-token", ".txt");
        try {
            Files.writeString(tokenFile, "tls-test-token-0123456789-0123456789\n");
            String token = Server.readSecretFile(tokenFile);
            check(token.equals("tls-test-token-0123456789-0123456789"), "one final newline removed");
            Files.writeString(tokenFile, " leading and trailing spaces \r\n");
            check(Server.readSecretFile(tokenFile).equals(" leading and trailing spaces "), "secret spaces preserved");
            Files.writeString(tokenFile, "");
            expectRejected(() -> Server.readSecretFile(tokenFile));
            Files.writeString(tokenFile, token + "\n");
            try {
                Server.createPlain(store, token, "0.0.0.0", 0);
                throw new AssertionError("plain HTTP bound to the network");
            } catch (IllegalStateException expected) { /* intentionally rejected */ }

            Path keyStore = Path.of(require("TLS_TEST_KEYSTORE"));
            Path passwordFile = Path.of(require("TLS_TEST_PASSWORD_FILE"));
            String keystorePassword = Server.readSecretFile(passwordFile);
            check(keystorePassword.startsWith(" ") && keystorePassword.endsWith(" "),
                    "TLS test password includes significant surrounding spaces");
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
                URI hold = URI.create("https://127.0.0.1:" + server.getAddress().getPort()
                        + "/holds?item=" + item + "&quantity=1&seconds=60");
                var rejectedWrite = client.send(HttpRequest.newBuilder(hold)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                check(rejectedWrite.statusCode() == 401, "TLS hold without token rejected");
                var unchanged = client.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                check(unchanged.body().equals("available=1"), "rejected TLS hold had no side effect");
                var created = client.send(HttpRequest.newBuilder(hold).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "tls-key-" + UUID.randomUUID())
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                check(created.statusCode() == 201 && created.body().startsWith("held id="), "TLS hold created");
                String id = created.body().substring("held id=".length());
                URI confirm = URI.create("https://127.0.0.1:" + server.getAddress().getPort()
                        + "/holds/" + id + "/confirm");
                var confirmed = client.send(HttpRequest.newBuilder(confirm).header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                check(confirmed.statusCode() == 200, "TLS hold confirmed");
                var finalRead = client.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                check(finalRead.body().equals("available=0"), "confirmed TLS hold consumes capacity");
                System.out.println("TLS integration tests passed");
            } finally { server.stop(0); }
        } finally { Files.deleteIfExists(tokenFile); }
    }

    private static void testConfiguration() throws Exception {
        Map<String, String> valid = Map.of("API_TOKEN_FILE", "/test/token",
                "DATABASE_PASSWORD_FILE", "/test/password");
        Server.validateTlsSecrets(valid);
        expectRejected(() -> Server.validateTlsSecrets(Map.of("DATABASE_PASSWORD_FILE", "/test/password")));
        expectRejected(() -> Server.validateTlsSecrets(Map.of("API_TOKEN_FILE", "/test/token")));
        expectRejected(() -> Server.validateTlsSecrets(Map.of("API_TOKEN_FILE", "/test/token",
                "DATABASE_PASSWORD_FILE", "/test/password", "API_TOKEN", "inline")));
        expectRejected(() -> Server.validateTlsSecrets(Map.of("API_TOKEN_FILE", "/test/token",
                "DATABASE_PASSWORD_FILE", "/test/password", "DATABASE_PASSWORD", "inline")));
    }

    private interface CheckedAction { void run() throws Exception; }
    private static void expectRejected(CheckedAction action) throws Exception {
        try { action.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("invalid secret configuration accepted");
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
