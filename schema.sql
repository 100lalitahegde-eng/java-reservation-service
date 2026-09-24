CREATE TABLE IF NOT EXISTS inventory (
    item TEXT PRIMARY KEY,
    capacity INTEGER NOT NULL CHECK (capacity >= 0)
);

CREATE TABLE IF NOT EXISTS holds (
    id TEXT PRIMARY KEY,
    item TEXT NOT NULL REFERENCES inventory(item),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    expires_at TIMESTAMPTZ NOT NULL,
    confirmed BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS holds_by_item ON holds(item);

CREATE TABLE IF NOT EXISTS requests (
    request_key TEXT PRIMARY KEY,
    item TEXT NOT NULL,
    quantity INTEGER NOT NULL,
    ttl_seconds BIGINT NOT NULL,
    hold_id TEXT UNIQUE REFERENCES holds(id)
);
