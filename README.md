# Reservation service (Java 17 + PostgreSQL)

A small HTTP service for finite inventory. PostgreSQL stores holds and idempotency records so commitments survive process restarts. Separate service instances use database transactions to coordinate reservations.

## Run

Create a PostgreSQL database, then initialise it with `psql` and `schema.sql`. The service uses a JDBC URL and credentials:

```sh
export DATABASE_URL='jdbc:postgresql://localhost:5432/reservations'
export DATABASE_USER='postgres'
export DATABASE_PASSWORD='your-local-password'
export API_TOKEN='replace-with-a-random-secret-of-at-least-32-characters'
psql 'postgresql://postgres@localhost:5432/reservations' -f schema.sql
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=classpath.txt
java -cp "target/classes:$(cat classpath.txt)" Server
```

The local HTTP mode binds to `127.0.0.1:8080` and seeds a `demo` item with capacity 10 if it does not exist. Example:

```sh
curl -H "Authorization: Bearer $API_TOKEN" 'http://127.0.0.1:8080/inventory?item=demo'
curl -X POST -H "Authorization: Bearer $API_TOKEN" -H 'Idempotency-Key: example-1' 'http://127.0.0.1:8080/holds?item=demo&quantity=2&seconds=60'
# Substitute the returned hold ID:
curl -X POST -H "Authorization: Bearer $API_TOKEN" 'http://127.0.0.1:8080/holds/ID/confirm'
```

## TLS and mounted secrets

For a network listener, use a certificate for the actual hostname in a PKCS#12 keystore. Mount the keystore and three secret files outside the repository with permissions restricted to the service account. Each secret file contains its exact value, optionally ending with one LF or CRLF line ending; leading and trailing spaces are preserved. Example environment configuration:

```sh
export LISTEN_HOST='0.0.0.0'
export DATABASE_URL='jdbc:postgresql://database.example:5432/reservations?sslmode=verify-full'
export DATABASE_USER='reservation_service'
export DATABASE_PASSWORD_FILE='/run/secrets/database-password'
export API_TOKEN_FILE='/run/secrets/api-token'
export TLS_KEYSTORE_FILE='/run/secrets/server.p12'
export TLS_KEYSTORE_PASSWORD_FILE='/run/secrets/keystore-password'
java -cp "target/classes:$(cat classpath.txt)" Server
```

`API_TOKEN` and `DATABASE_PASSWORD` environment variables remain available for local development. TLS mode requires file-backed token and database password, and rejects a simultaneous inline value. Plain HTTP refuses to bind to a non-loopback address. This block is illustrative configuration, not a deployment recipe. The mounting environment must restrict file access to the service account; the service reads credentials and the certificate at startup, so rotation requires a restart. The repository contains no real certificate or secret. Provision a trusted certificate, database trust roots and PostgreSQL client trust configuration, a secret store, network policy, and a hosting environment before exposing this service. The example hostname is illustrative; CI does not verify database TLS or public certificate trust.

## Invariant and transaction design

`available = capacity - confirmed quantity - unexpired hold quantity`. A hold transaction first claims a unique request key. It then locks the inventory row with `SELECT ... FOR UPDATE`, checks available capacity, creates the hold, and links it to the key before commit. Concurrent requests for the same item serialize at that row, including across service instances. Confirmation acquires the same inventory lock before the hold lock; it rejects expired holds using the database clock. This order prevents an expired hold from being replaced and then confirmed.

The request key is retained permanently. Replaying an identical key, item, quantity, and TTL returns its original hold, even after expiration or confirmation. A changed payload returns 409. A failed capacity attempt rolls back the key so it can be retried.

## Verification

GitHub Actions starts PostgreSQL and runs four test programs. `ReservationStoreTest` checks the original in-memory contention model. `DatabaseReservationStoreTest` creates competing holds through separate store instances, checks durable confirmation through a fresh instance, verifies expiry and replay, and races one key across different items. `ServerHttpTest` sends real loopback requests and checks unauthorised reads and writes, authorised hold/confirm/replay, and absence of write side effects after a 401. `ServerTlsTest` uses a disposable certificate explicitly trusted by its client to verify a real TLS handshake, authorised and rejected reads, a rejected TLS hold, an authorised hold and confirmation, no inventory change after the rejected hold, file-backed token reading, TLS configuration validation, and refusal to bind plain HTTP to a network address. It calls the server factories and configuration validator directly; it does not run the full production startup path. To run the first three locally, use the Maven command above, then:

```sh
java -cp "target/classes:target/test-classes:$(cat classpath.txt)" ReservationStoreTest
java -cp "target/classes:target/test-classes:$(cat classpath.txt)" DatabaseReservationStoreTest
java -cp "target/classes:target/test-classes:$(cat classpath.txt)" ServerHttpTest
```

## Boundaries

- A single bearer token is required on every endpoint. It is a shared credential, not a user identity or roles system. Rotation currently requires a restart. A real deployment also needs a secret manager, audit policy, and rate limiting.
- The TLS mode accepts a mounted keystore and file-backed secrets, but it does not procure or renew certificates, enforce file permissions, deploy infrastructure, or enforce database TLS. Configure and verify database TLS separately. These integration tests use a disposable test certificate and local PostgreSQL.
- There is no JSON API, schema migration tool, connection pool, or hosting configuration.
- The fixed demo stock initializer never changes existing inventory. Real stock adjustments need their own guarded transaction.
- Expired hold rows and request keys are retained for audit and idempotency; production needs an explicit retention policy.
- HTTP integration tests cover the happy path and authentication failures, but not every malformed input or network failure.
- No throughput claim is made. Benchmark with documented hardware, a fixed request mix, and latency percentiles before making performance claims.
