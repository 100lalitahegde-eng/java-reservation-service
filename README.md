# Reservation service

HTTP API for a finite inventory. A client places a time-bounded hold, then confirms it. PostgreSQL stores holds and idempotency keys, so a restart keeps the commitment and separate processes share one capacity count.

## Design

`available = capacity - confirmed quantity - unexpired hold quantity`.

A hold runs in one transaction:

1. Insert the idempotency key. The unique index serializes concurrent uses of that key, including uses that name different items.
2. Lock the inventory row with `SELECT ... FOR UPDATE` and count confirmed plus unexpired holds.
3. Insert the hold and attach it to the key. A capacity failure rolls the transaction back, key included, so the client can retry that key.

The key is kept after commit. Replaying the same item, quantity, and TTL returns the original hold, including after expiry or confirmation. A different payload returns 409.

Confirmation locks the inventory row before the hold row, the same order a hold uses, then compares `expires_at` with `clock_timestamp()`. An expired hold cannot be confirmed, and a replacement cannot land underneath a confirmation that already holds the inventory row.

The JDK HTTP server runs requests on a fixed thread pool with a bounded queue. When the pool and the queue are both full, the acceptor thread runs the request, so a saturated process accepts new connections more slowly. The pool shuts down with the server. JDBC connections come from a HikariCP pool. Every route requires a bearer token of at least 32 characters, compared with `MessageDigest.isEqual`. Plain HTTP binds only to a loopback address. A non-loopback bind requires TLS, a PKCS#12 keystore, and file-backed secrets.

`ReservationStoreTest` races the in-memory single-lock model. `DatabaseReservationStoreTest` races two store instances against PostgreSQL, confirms through a new instance, and covers expiry, replay, and one key used for two items. `ServerHttpTest` and `ServerTlsTest` call the listener over the network, including a TLS handshake with a certificate the test client trusts explicitly.

## Build and test

Java 17 and PostgreSQL 16. `mvn test` applies `schema.sql` to the database in `DATABASE_URL`.

```sh
export DATABASE_URL='jdbc:postgresql://localhost:5432/reservations'
export DATABASE_USER='postgres'
export DATABASE_PASSWORD='postgres'
mvn test
```

GitHub Actions runs the same command against a PostgreSQL 16 service.

## Run

```sh
export DATABASE_URL='jdbc:postgresql://localhost:5432/reservations'
export DATABASE_USER='postgres'
export DATABASE_PASSWORD='your-password'
export API_TOKEN='replace-with-a-random-secret-of-at-least-32-characters'
psql 'postgresql://postgres@localhost:5432/reservations' -f schema.sql
mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=classpath.txt
java -cp "target/reservation-service-0.2.0.jar:$(cat classpath.txt)" dev.lalitahegade.reservation.Server
```

The process listens on `127.0.0.1:8080`. It inserts item `demo` with capacity 10 when that item is absent.

```sh
curl -H "Authorization: Bearer $API_TOKEN" 'http://127.0.0.1:8080/inventory?item=demo'
curl -X POST -H "Authorization: Bearer $API_TOKEN" -H 'Idempotency-Key: example-1' \
  'http://127.0.0.1:8080/holds?item=demo&quantity=2&seconds=60'
curl -X POST -H "Authorization: Bearer $API_TOKEN" 'http://127.0.0.1:8080/holds/HOLD_ID/confirm'
```

### TLS listener

Set `LISTEN_HOST` to a non-loopback address together with a PKCS#12 keystore for that host. Each secret file is the raw value. One trailing LF or CRLF is removed; other whitespace stays. TLS mode requires `API_TOKEN_FILE` and `DATABASE_PASSWORD_FILE` and rejects an inline value for the same secret.

```sh
export LISTEN_HOST='0.0.0.0'
export DATABASE_URL='jdbc:postgresql://database.example:5432/reservations?sslmode=verify-full'
export DATABASE_USER='reservation_service'
export DATABASE_PASSWORD_FILE='/run/secrets/database-password'
export API_TOKEN_FILE='/run/secrets/api-token'
export TLS_KEYSTORE_FILE='/run/secrets/server.p12'
export TLS_KEYSTORE_PASSWORD_FILE='/run/secrets/keystore-password'
java -cp "target/reservation-service-0.2.0.jar:$(cat classpath.txt)" dev.lalitahegade.reservation.Server
```

The service account should be the only user that can read those files. Values are loaded at startup, so a rotation is a restart.

## Limits

- One shared bearer token on every route. No per-user identity, roles, or rate limits. Changing the token means a restart.
- Routes take query parameters and return `text/plain`. `schema.sql` is applied directly; there is no migration tool.
- Idempotency keys and expired hold rows stay until an operator removes them.
- Startup inserts `demo` only when the item is absent. It does not change a capacity that already exists.
- Certificate issuance, secret-file permissions, and database TLS are set up outside this process.
