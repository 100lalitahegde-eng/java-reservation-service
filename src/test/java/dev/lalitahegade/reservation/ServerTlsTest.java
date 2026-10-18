package dev.lalitahegade.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** TLS handshake with a certificate generated for this test and trusted by the client. */
class ServerTlsTest {
    private static final String PASSWORD = " test-password-for-ci-only ";

    private static Path directory;
    private static Path keyStore;
    private static Path passwordFile;
    private static Path trustStore;
    private static DatabaseReservationStore store;

    @BeforeAll
    static void prepare() throws Exception {
        PostgresSupport.applySchema();
        store = PostgresSupport.store();
        directory = Files.createTempDirectory("reservation-tls");
        keyStore = directory.resolve("server.p12");
        trustStore = directory.resolve("trust.p12");
        passwordFile = directory.resolve("password.txt");
        Files.writeString(passwordFile, PASSWORD + "\n");
        Path certificate = directory.resolve("server.pem");
        keytool("-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=localhost", "-ext", "SAN=IP:127.0.0.1,DNS:localhost",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt");
        keytool("-exportcert", "-alias", "test", "-keystore", keyStore.toString(),
                "-storepass", PASSWORD, "-rfc", "-file", certificate.toString());
        keytool("-importcert", "-alias", "test", "-file", certificate.toString(),
                "-keystore", trustStore.toString(), "-storetype", "PKCS12",
                "-storepass", PASSWORD, "-noprompt");
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (store != null) {
            store.close();
        }
        if (directory != null) {
            try (var walk = Files.walk(directory)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // Best-effort removal of a temp directory.
                    }
                });
            }
        }
    }

    @Test
    void tlsModeRequiresFileBackedSecretsOnly() {
        Server.validateTlsSecrets(Map.of(
                "API_TOKEN_FILE", "/test/token",
                "DATABASE_PASSWORD_FILE", "/test/password"));
        assertThrows(IllegalStateException.class, () -> Server.validateTlsSecrets(
                Map.of("DATABASE_PASSWORD_FILE", "/test/password")));
        assertThrows(IllegalStateException.class, () -> Server.validateTlsSecrets(
                Map.of("API_TOKEN_FILE", "/test/token")));
        assertThrows(IllegalStateException.class, () -> Server.validateTlsSecrets(Map.of(
                "API_TOKEN_FILE", "/test/token",
                "DATABASE_PASSWORD_FILE", "/test/password",
                "API_TOKEN", "inline")));
        assertThrows(IllegalStateException.class, () -> Server.validateTlsSecrets(Map.of(
                "API_TOKEN_FILE", "/test/token",
                "DATABASE_PASSWORD_FILE", "/test/password",
                "DATABASE_PASSWORD", "inline")));
    }

    @Test
    void secretFilesKeepInternalWhitespaceAndDropOneTrailingNewline() throws Exception {
        Path tokenFile = Files.createTempFile("reservation-test-token", ".txt");
        try {
            Files.writeString(tokenFile, "tls-test-token-0123456789-0123456789\n");
            assertEquals("tls-test-token-0123456789-0123456789", Server.readSecretFile(tokenFile));
            Files.writeString(tokenFile, " leading and trailing spaces \r\n");
            assertEquals(" leading and trailing spaces ", Server.readSecretFile(tokenFile));
            Files.writeString(tokenFile, "");
            assertThrows(IllegalStateException.class, () -> Server.readSecretFile(tokenFile));
        } finally {
            Files.deleteIfExists(tokenFile);
        }
    }

    @Test
    void plainHttpRefusesANonLoopbackAddress() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> Server.createPlain(store, "tls-test-token-0123456789-0123456789", "0.0.0.0", 0));
    }

    @Test
    void authorisedTlsHoldAndConfirm() throws Exception {
        String item = "tls-" + UUID.randomUUID();
        store.stockIfAbsent(item, 1);
        String token = "tls-test-token-0123456789-0123456789";
        try (Server server = Server.createTls(store, token, "127.0.0.1", 0, keyStore, passwordFile)) {
            server.start();
            KeyStore trust = KeyStore.getInstance("PKCS12");
            char[] password = Server.readSecretFile(passwordFile).toCharArray();
            try (var input = Files.newInputStream(trustStore)) {
                trust.load(input, password);
            } finally {
                Arrays.fill(password, '\0');
            }
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers.getTrustManagers(), null);
            HttpClient client = HttpClient.newBuilder().sslContext(context).build();
            URI inventory = URI.create("https://127.0.0.1:" + server.address().getPort() + "/inventory?item=" + item);
            HttpResponse<String> denied = client.send(
                    HttpRequest.newBuilder(inventory).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, denied.statusCode(), "TLS request without token rejected");
            HttpResponse<String> accepted = client.send(
                    HttpRequest.newBuilder(inventory).header("Authorization", "Bearer " + token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, accepted.statusCode());
            assertEquals("available=1", accepted.body(), "trusted TLS request accepted");
            assertFalse(accepted.body().contains(token), "secret not echoed");

            URI hold = URI.create("https://127.0.0.1:" + server.address().getPort()
                    + "/holds?item=" + item + "&quantity=1&seconds=60");
            HttpResponse<String> rejectedWrite = client.send(
                    HttpRequest.newBuilder(hold).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, rejectedWrite.statusCode(), "TLS hold without token rejected");
            HttpResponse<String> unchanged = client.send(
                    HttpRequest.newBuilder(inventory).header("Authorization", "Bearer " + token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("available=1", unchanged.body(), "rejected TLS hold had no side effect");

            HttpResponse<String> created = client.send(
                    HttpRequest.newBuilder(hold)
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "tls-key-" + UUID.randomUUID())
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, created.statusCode());
            assertTrue(created.body().startsWith("held id="), "TLS hold created");
            String id = created.body().substring("held id=".length());
            URI confirm = URI.create("https://127.0.0.1:" + server.address().getPort() + "/holds/" + id + "/confirm");
            HttpResponse<String> confirmed = client.send(
                    HttpRequest.newBuilder(confirm)
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, confirmed.statusCode(), "TLS hold confirmed");
            HttpResponse<String> finalRead = client.send(
                    HttpRequest.newBuilder(inventory).header("Authorization", "Bearer " + token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("available=0", finalRead.body(), "confirmed TLS hold consumes capacity");
        }
    }

    private static void keytool(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException("keytool failed (" + code + "): " + output);
        }
    }
}
