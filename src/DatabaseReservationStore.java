import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

/** PostgreSQL-backed reservation transitions. Each operation uses a fresh connection. */
public final class DatabaseReservationStore {
    private final String url;
    private final String user;
    private final String password;

    public DatabaseReservationStore(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    private Connection connect() throws SQLException { return DriverManager.getConnection(url, user, password); }

    /** Demo setup only: never changes an existing item's capacity. */
    public void stockIfAbsent(String item, int capacity) throws SQLException {
        if (item == null || item.isBlank() || capacity < 0) throw new IllegalArgumentException();
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO inventory(item, capacity) VALUES (?, ?) ON CONFLICT (item) DO NOTHING")) {
            s.setString(1, item);
            s.setInt(2, capacity);
            s.executeUpdate();
        }
    }

    public ReservationStore.Result hold(String item, int quantity, long seconds, String key) throws SQLException {
        if (key == null || key.isBlank() || item == null || item.isBlank()
                || quantity <= 0 || seconds <= 0 || seconds > 3600)
            return new ReservationStore.Result(400, "invalid request", null);
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                // A unique key is claimed first. Concurrent requests with the same key wait
                // for the first transaction to finish, even when they target different items.
                int claimed;
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO requests(request_key, item, quantity, ttl_seconds) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT (request_key) DO NOTHING")) {
                    s.setString(1, key); s.setString(2, item); s.setInt(3, quantity); s.setLong(4, seconds);
                    claimed = s.executeUpdate();
                }
                if (claimed == 0) {
                    ReservationStore.Result replay = replay(c, key, item, quantity, seconds);
                    c.commit();
                    return replay;
                }
                Integer capacity = null;
                try (PreparedStatement s = c.prepareStatement("SELECT capacity FROM inventory WHERE item = ? FOR UPDATE")) {
                    s.setString(1, item);
                    try (ResultSet rows = s.executeQuery()) { if (rows.next()) capacity = rows.getInt(1); }
                }
                if (capacity == null) { c.rollback(); return new ReservationStore.Result(404, "unknown item", null); }
                long used;
                try (PreparedStatement s = c.prepareStatement(
                        "SELECT COALESCE(SUM(quantity), 0) FROM holds WHERE item = ? " +
                        "AND (confirmed OR expires_at > clock_timestamp())")) {
                    s.setString(1, item);
                    try (ResultSet rows = s.executeQuery()) { rows.next(); used = rows.getLong(1); }
                }
                if (capacity - used < quantity) {
                    c.rollback();
                    return new ReservationStore.Result(409, "insufficient capacity", null);
                }
                String id = UUID.randomUUID().toString();
                ReservationStore.Hold hold;
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO holds(id, item, quantity, expires_at, confirmed) " +
                        "VALUES (?, ?, ?, clock_timestamp() + (? * INTERVAL '1 second'), false) RETURNING expires_at")) {
                    s.setString(1, id); s.setString(2, item); s.setInt(3, quantity); s.setLong(4, seconds);
                    try (ResultSet rows = s.executeQuery()) {
                        rows.next();
                        hold = new ReservationStore.Hold(id, item, quantity, rows.getTimestamp(1).toInstant(), false);
                    }
                }
                try (PreparedStatement s = c.prepareStatement("UPDATE requests SET hold_id = ? WHERE request_key = ?")) {
                    s.setString(1, id); s.setString(2, key); s.executeUpdate();
                }
                c.commit();
                return new ReservationStore.Result(201, "held", hold);
            } catch (SQLException | RuntimeException ex) { c.rollback(); throw ex; }
        }
    }

    private ReservationStore.Result replay(Connection c, String key, String item, int quantity, long seconds)
            throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT r.item, r.quantity, r.ttl_seconds, h.id, h.expires_at, h.confirmed " +
                "FROM requests r JOIN holds h ON h.id = r.hold_id WHERE r.request_key = ?")) {
            s.setString(1, key);
            try (ResultSet rows = s.executeQuery()) {
                if (!rows.next()) throw new SQLException("committed request has no hold");
                if (!item.equals(rows.getString(1)) || quantity != rows.getInt(2) || seconds != rows.getLong(3))
                    return new ReservationStore.Result(409, "request id already used", null);
                var previous = new ReservationStore.Hold(rows.getString(4), item, quantity,
                        rows.getTimestamp(5).toInstant(), rows.getBoolean(6));
                return new ReservationStore.Result(200, "replayed", previous);
            }
        }
    }

    public ReservationStore.Result confirm(String id) throws SQLException {
        if (id == null || id.isBlank()) return new ReservationStore.Result(400, "invalid id", null);
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                String item;
                try (PreparedStatement s = c.prepareStatement("SELECT item FROM holds WHERE id = ?")) {
                    s.setString(1, id);
                    try (ResultSet rows = s.executeQuery()) {
                        if (!rows.next()) { c.rollback(); return new ReservationStore.Result(404, "hold missing", null); }
                        item = rows.getString(1);
                    }
                }
                // Match hold creation's lock order: inventory row before hold row.
                // Otherwise an expiring hold could be replaced while it is confirmed.
                try (PreparedStatement s = c.prepareStatement("SELECT capacity FROM inventory WHERE item = ? FOR UPDATE")) {
                    s.setString(1, item);
                    try (ResultSet rows = s.executeQuery()) {
                        if (!rows.next()) throw new SQLException("hold references missing inventory");
                    }
                }
                ReservationStore.Hold prior;
                try (PreparedStatement s = c.prepareStatement(
                        "SELECT item, quantity, expires_at, confirmed FROM holds WHERE id = ? FOR UPDATE")) {
                    s.setString(1, id);
                    try (ResultSet rows = s.executeQuery()) {
                        if (!rows.next()) { c.rollback(); return new ReservationStore.Result(404, "hold missing", null); }
                        prior = new ReservationStore.Hold(id, rows.getString(1), rows.getInt(2),
                                rows.getTimestamp(3).toInstant(), rows.getBoolean(4));
                    }
                }
                if (prior.confirmed()) { c.commit(); return new ReservationStore.Result(200, "already confirmed", prior); }
                // Compare against the database clock after acquiring the hold lock.
                try (Statement s = c.createStatement(); ResultSet rows = s.executeQuery("SELECT clock_timestamp()")) {
                    rows.next();
                    Instant now = rows.getTimestamp(1).toInstant();
                    if (!prior.expiresAt().isAfter(now)) {
                        c.rollback(); return new ReservationStore.Result(404, "hold expired", null);
                    }
                }
                try (PreparedStatement s = c.prepareStatement("UPDATE holds SET confirmed = true WHERE id = ?")) {
                    s.setString(1, id); s.executeUpdate();
                }
                c.commit();
                return new ReservationStore.Result(200, "confirmed",
                        new ReservationStore.Hold(id, prior.item(), prior.quantity(), prior.expiresAt(), true));
            } catch (SQLException | RuntimeException ex) { c.rollback(); throw ex; }
        }
    }

    public long available(String item) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "SELECT i.capacity - COALESCE(SUM(h.quantity) FILTER " +
                "(WHERE h.confirmed OR h.expires_at > clock_timestamp()), 0) " +
                "FROM inventory i LEFT JOIN holds h ON h.item = i.item WHERE i.item = ? GROUP BY i.capacity")) {
            s.setString(1, item);
            try (ResultSet rows = s.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
        }
    }
}
