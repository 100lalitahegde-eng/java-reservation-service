package dev.lalitahegade.reservation;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** In-memory capacity model. Every transition shares one lock. */
public final class ReservationStore {
    public record Hold(String id, String item, int quantity, Instant expiresAt, boolean confirmed) {}

    public record Result(int status, String message, Hold hold) {}

    private record Request(String item, int quantity, long seconds, String holdId) {}

    private final Map<String, Integer> capacities = new HashMap<>();
    private final Map<String, Hold> holds = new HashMap<>();
    /** Expired holds stay here so a replayed request id still returns its original hold. */
    private final Map<String, Hold> expired = new HashMap<>();
    private final Map<String, Request> requests = new HashMap<>();
    private final Clock clock;

    public ReservationStore(Clock clock) {
        this.clock = clock;
    }

    public synchronized void stock(String item, int capacity) {
        if (item == null || item.isBlank() || capacity < 0) {
            throw new IllegalArgumentException();
        }
        capacities.put(item, capacity);
    }

    public synchronized Result hold(String item, int quantity, long seconds, String requestId) {
        expire();
        if (requestId == null || requestId.isBlank() || item == null || item.isBlank()
                || quantity <= 0 || seconds <= 0 || seconds > 3600) {
            return new Result(400, "invalid request", null);
        }
        Request existing = requests.get(requestId);
        if (existing != null) {
            if (!existing.item().equals(item) || existing.quantity() != quantity || existing.seconds() != seconds) {
                return new Result(409, "request id already used", null);
            }
            Hold prior = holds.containsKey(existing.holdId())
                    ? holds.get(existing.holdId())
                    : expired.get(existing.holdId());
            return new Result(200, "replayed", prior);
        }
        if (!capacities.containsKey(item)) {
            return new Result(404, "unknown item", null);
        }
        if (available(item) < quantity) {
            return new Result(409, "insufficient capacity", null);
        }
        Hold created = new Hold(
                UUID.randomUUID().toString(), item, quantity, clock.instant().plusSeconds(seconds), false);
        holds.put(created.id(), created);
        requests.put(requestId, new Request(item, quantity, seconds, created.id()));
        return new Result(201, "held", created);
    }

    public synchronized Result confirm(String id) {
        expire();
        Hold hold = holds.get(id);
        if (hold == null) {
            return new Result(404, "hold missing or expired", null);
        }
        if (hold.confirmed()) {
            return new Result(200, "already confirmed", hold);
        }
        Hold confirmed = new Hold(hold.id(), hold.item(), hold.quantity(), hold.expiresAt(), true);
        holds.put(id, confirmed);
        return new Result(200, "confirmed", confirmed);
    }

    public synchronized int available(String item) {
        expire();
        int used = holds.values().stream()
                .filter(hold -> hold.item().equals(item))
                .mapToInt(Hold::quantity)
                .sum();
        return capacities.getOrDefault(item, 0) - used;
    }

    private void expire() {
        Instant now = clock.instant();
        Iterator<Hold> active = holds.values().iterator();
        while (active.hasNext()) {
            Hold hold = active.next();
            if (!hold.confirmed() && !hold.expiresAt().isAfter(now)) {
                expired.put(hold.id(), hold);
                active.remove();
            }
        }
    }
}
