package dev.lalitahegade.reservation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

final class PostgresSupport {
    static final String URL = require("DATABASE_URL");
    static final String USER = System.getenv().getOrDefault("DATABASE_USER", "postgres");
    static final String PASSWORD = System.getenv().getOrDefault("DATABASE_PASSWORD", "");

    private PostgresSupport() {}

    static void applySchema() throws IOException, SQLException {
        Path schema = Path.of("schema.sql");
        if (!Files.isRegularFile(schema)) {
            throw new IllegalStateException("schema.sql not found in " + Path.of("").toAbsolutePath());
        }
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(schema).split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    static DatabaseReservationStore store() {
        return new DatabaseReservationStore(URL, USER, PASSWORD);
    }

    private static String require(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required");
        }
        return value;
    }
}
