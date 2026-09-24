# Reservation service (Java 17 + PostgreSQL)

A small HTTP service for finite inventory. PostgreSQL stores holds and idempotency records so commitments survive process restarts. Separate service instances use database transactions to coordinate reservations.

## Run

Create a PostgreSQL database, then initialise it with `psql` and `schema.sql`. The service uses a JDBC URL and credentials:

```sh
export DATABASE_URL='jdbc:postgresql://localhost:5432/reservations'
export DATABASE_USER='postgres'
export DATABASE_PASSWORD='your-local-password'
psql 'postgresql://postgres@localhost:5432/reservations' -f schema.sql
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=classpath.txt
java -cp "target/classes:$(cat classpath.txt)" Server
```

The server binds only to `127.0.0.1:8080` and seeds a `demo` item with capacity 10 if it does not exist. Example:

```sh
curl 'http://127.0.0.1:8080/inventory?item=demo'
curl -X POST -H 'Idempotency-Key: example-1' 'http://127.0.0.1:8080/holds?item=demo&quantity=2&seconds=60'
# Substitute the returned hold ID:
curl -X POST 'http://127.0.0.1:8080/holds/ID/confirm'
```

## Invariant and transaction design

`available = capacity - confirmed quantity - unexpired hold quantity`. A hold transaction first claims a unique request key. It then locks the inventory row with `SELECT ... FOR UPDATE`, checks available capacity, creates the hold, and links it to the key before commit. Concurrent requests for the same item serialize at that row, including across service instances. Confirmation acquires the same inventory lock before the hold lock; it rejects expired holds using the database clock. This order prevents an expired hold from being replaced and then confirmed.

The request key is retained permanently. Replaying an identical key, item, quantity, and TTL returns its original hold, even after expiration or confirmation. A changed payload returns 409. A failed capacity attempt rolls back the key so it can be retried.

## Verification

GitHub Actions starts PostgreSQL and runs both test programs. `ReservationStoreTest` checks the original in-memory contention model. `DatabaseReservationStoreTest` creates competing holds through separate store instances, checks durable confirmation through a fresh instance, verifies expiry and replay, and races one key across different items. To run locally, use the Maven command above, then:

```sh
java -cp "target/classes:target/test-classes:$(cat classpath.txt)" ReservationStoreTest
java -cp "target/classes:target/test-classes:$(cat classpath.txt)" DatabaseReservationStoreTest
```

## Boundaries

- This is a portfolio prototype: no authentication, JSON API, rate limits, schema migration tool, connection pool, or deployment configuration. Keep it on localhost.
- The fixed demo stock initializer never changes existing inventory. Real stock adjustments need their own guarded transaction.
- Expired hold rows and request keys are retained for audit and idempotency; production needs an explicit retention policy.
- The HTTP adapter has no integration tests yet. The database tests target the transaction boundary, not network behaviour.
- No throughput claim is made. Benchmark with documented hardware, a fixed request mix, and latency percentiles before making performance claims.
