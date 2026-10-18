package dev.lalitahegade.reservation;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

/** PostgreSQL-backed reservation transitions. Connections come from one pool. */
public final class DatabaseReservationStore implements AutoCloseable {
    private static final int POOL_SIZE = 10;

    private final HikariDataSource dataSource;

    public DatabaseReservationStore(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(POOL_SIZE);
        config.setPoolName("reservation");
        this.dataSource = new HikariDataSource(config);
    }

    private Connection connect() throws SQLException {
        return dataSource.getConnection();
    }

    /** Demo setup only: never changes an existing item's capacity. */
    public void stockIfAbsent(String item, int capacity) throws SQLException {
        if (item == null || item.isBlank() || capacity < 0) {
            throw new IllegalArgumentException();
        }
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO inventory(item, capacity) VALUES (?, ?) ON CONFLICT (item) DO NOTHING")) {
            statement.setString(1, item);
            statement.setInt(2, capacity);
            statement.executeUpdate();
        }
    }

    public ReservationStore.Result hold(String item, int quantity, long seconds, String key) throws SQLException {
        if (key == null || key.isBlank() || item == null || item.isBlank()
                || quantity <= 0 || seconds <= 0 || seconds > 3600) {
            return new ReservationStore.Result(400, "invalid request", null);
        }
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                // The unique key is claimed first. A second transaction with the same key waits
                // for this one to finish, including when the two requests name different items.
                int claimed;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO requests(request_key, item, quantity, ttl_seconds) VALUES (?, ?, ?, ?) "
                                + "ON CONFLICT (request_key) DO NOTHING")) {
                    statement.setString(1, key);
                    statement.setString(2, item);
                    statement.setInt(3, quantity);
                    statement.setLong(4, seconds);
                    claimed = statement.executeUpdate();
                }
                if (claimed == 0) {
                    ReservationStore.Result replay = replay(connection, key, item, quantity, seconds);
                    connection.commit();
                    return replay;
                }
                Integer capacity = null;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT capacity FROM inventory WHERE item = ? FOR UPDATE")) {
                    statement.setString(1, item);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (rows.next()) {
                            capacity = rows.getInt(1);
                        }
                    }
                }
                if (capacity == null) {
                    connection.rollback();
                    return new ReservationStore.Result(404, "unknown item", null);
                }
                long used;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COALESCE(SUM(quantity), 0) FROM holds WHERE item = ? "
                                + "AND (confirmed OR expires_at > clock_timestamp())")) {
                    statement.setString(1, item);
                    try (ResultSet rows = statement.executeQuery()) {
                        rows.next();
                        used = rows.getLong(1);
                    }
                }
                if (capacity - used < quantity) {
                    connection.rollback();
                    return new ReservationStore.Result(409, "insufficient capacity", null);
                }
                String id = UUID.randomUUID().toString();
                ReservationStore.Hold hold;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO holds(id, item, quantity, expires_at, confirmed) "
                                + "VALUES (?, ?, ?, clock_timestamp() + (? * INTERVAL '1 second'), false) "
                                + "RETURNING expires_at")) {
                    statement.setString(1, id);
                    statement.setString(2, item);
                    statement.setInt(3, quantity);
                    statement.setLong(4, seconds);
                    try (ResultSet rows = statement.executeQuery()) {
                        rows.next();
                        hold = new ReservationStore.Hold(
                                id, item, quantity, rows.getTimestamp(1).toInstant(), false);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE requests SET hold_id = ? WHERE request_key = ?")) {
                    statement.setString(1, id);
                    statement.setString(2, key);
                    statement.executeUpdate();
                }
                connection.commit();
                return new ReservationStore.Result(201, "held", hold);
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    private ReservationStore.Result replay(Connection connection, String key, String item, int quantity, long seconds)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT r.item, r.quantity, r.ttl_seconds, h.id, h.expires_at, h.confirmed "
                        + "FROM requests r JOIN holds h ON h.id = r.hold_id WHERE r.request_key = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("committed request has no hold");
                }
                if (!item.equals(rows.getString(1)) || quantity != rows.getInt(2) || seconds != rows.getLong(3)) {
                    return new ReservationStore.Result(409, "request id already used", null);
                }
                ReservationStore.Hold previous = new ReservationStore.Hold(
                        rows.getString(4), item, quantity, rows.getTimestamp(5).toInstant(), rows.getBoolean(6));
                return new ReservationStore.Result(200, "replayed", previous);
            }
        }
    }

    public ReservationStore.Result confirm(String id) throws SQLException {
        if (id == null || id.isBlank()) {
            return new ReservationStore.Result(400, "invalid id", null);
        }
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                String item;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT item FROM holds WHERE id = ?")) {
                    statement.setString(1, id);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            connection.rollback();
                            return new ReservationStore.Result(404, "hold missing", null);
                        }
                        item = rows.getString(1);
                    }
                }
                // Same lock order as hold creation: inventory row, then hold row.
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT capacity FROM inventory WHERE item = ? FOR UPDATE")) {
                    statement.setString(1, item);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new SQLException("hold references missing inventory");
                        }
                    }
                }
                ReservationStore.Hold prior;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT item, quantity, expires_at, confirmed FROM holds WHERE id = ? FOR UPDATE")) {
                    statement.setString(1, id);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            connection.rollback();
                            return new ReservationStore.Result(404, "hold missing", null);
                        }
                        prior = new ReservationStore.Hold(
                                id,
                                rows.getString(1),
                                rows.getInt(2),
                                rows.getTimestamp(3).toInstant(),
                                rows.getBoolean(4));
                    }
                }
                if (prior.confirmed()) {
                    connection.commit();
                    return new ReservationStore.Result(200, "already confirmed", prior);
                }
                try (Statement statement = connection.createStatement();
                        ResultSet rows = statement.executeQuery("SELECT clock_timestamp()")) {
                    rows.next();
                    Instant now = rows.getTimestamp(1).toInstant();
                    if (!prior.expiresAt().isAfter(now)) {
                        connection.rollback();
                        return new ReservationStore.Result(404, "hold expired", null);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE holds SET confirmed = true WHERE id = ?")) {
                    statement.setString(1, id);
                    statement.executeUpdate();
                }
                connection.commit();
                return new ReservationStore.Result(
                        200,
                        "confirmed",
                        new ReservationStore.Hold(id, prior.item(), prior.quantity(), prior.expiresAt(), true));
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    public long available(String item) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT i.capacity - COALESCE(SUM(h.quantity) FILTER "
                                + "(WHERE h.confirmed OR h.expires_at > clock_timestamp()), 0) "
                                + "FROM inventory i LEFT JOIN holds h ON h.item = i.item "
                                + "WHERE i.item = ? GROUP BY i.capacity")) {
            statement.setString(1, item);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
