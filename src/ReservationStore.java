import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** All inventory transitions share one lock. This makes capacity checks and updates atomic. */
public final class ReservationStore {
    public record Hold(String id, String item, int quantity, Instant expiresAt, boolean confirmed) {}
    public record Result(int status, String message, Hold hold) {}
    private final Map<String, Integer> capacities = new HashMap<>();
    private final Map<String, Hold> holds = new HashMap<>();
    private final Map<String, String> requests = new HashMap<>();
    private final Clock clock;

    public ReservationStore(Clock clock) { this.clock = clock; }

    public synchronized void stock(String item, int capacity) {
        if (item == null || item.isBlank() || capacity < 0) throw new IllegalArgumentException();
        capacities.put(item, capacity);
    }

    public synchronized Result hold(String item, int quantity, long seconds, String requestId) {
        expire();
        if (requestId == null || requestId.isBlank() || item == null || item.isBlank()
                || quantity <= 0 || seconds <= 0 || seconds > 3600) return new Result(400, "invalid request", null);
        String existing = requests.get(requestId);
        if (existing != null) {
            Hold prior = holds.get(existing);
            if (prior != null && prior.item().equals(item) && prior.quantity() == quantity)
                return new Result(200, "replayed", prior);
            return new Result(409, "request id already used", null);
        }
        if (!capacities.containsKey(item)) return new Result(404, "unknown item", null);
        int available = available(item);
        if (available < quantity) return new Result(409, "insufficient capacity", null);
        Hold created = new Hold(UUID.randomUUID().toString(), item, quantity, clock.instant().plusSeconds(seconds), false);
        holds.put(created.id(), created);
        requests.put(requestId, created.id());
        return new Result(201, "held", created);
    }

    public synchronized Result confirm(String id) {
        expire();
        Hold h = holds.get(id);
        if (h == null) return new Result(404, "hold missing or expired", null);
        if (h.confirmed()) return new Result(200, "already confirmed", h);
        Hold confirmed = new Hold(h.id(), h.item(), h.quantity(), h.expiresAt(), true);
        holds.put(id, confirmed);
        return new Result(200, "confirmed", confirmed);
    }

    public synchronized int available(String item) {
        expire();
        int used = holds.values().stream().filter(h -> h.item().equals(item)).mapToInt(Hold::quantity).sum();
        return capacities.getOrDefault(item, 0) - used;
    }

    private void expire() {
        Instant now = clock.instant();
        holds.values().removeIf(h -> !h.confirmed() && !h.expiresAt().isAfter(now));
    }
}
