# Reservation service (Java 17)

A single-process HTTP prototype for finite inventory. It demonstrates a capacity invariant under concurrent requests. No external dependencies are required.

## Run

```sh
javac -d out src/*.java test/*.java
java -cp out ReservationStoreTest
java -cp out Server
```

In another terminal:

```sh
curl 'http://127.0.0.1:8080/inventory?item=demo'
curl -X POST -H 'Idempotency-Key: example-1' 'http://127.0.0.1:8080/holds?item=demo&quantity=2&seconds=60'
# Use the returned id:
curl -X POST 'http://127.0.0.1:8080/holds/ID/confirm'
```

## Design and invariant

`ReservationStore` guards capacity checks, hold creation, expiry, and confirmation with a single monitor. For each item, `available = capacity - active holds - confirmed reservations` never falls below zero through this API. A repeated idempotency key with the same item and quantity returns its original hold; a conflicting key returns 409. The contention test submits 40 requests for one seat and expects exactly one success.

## Limits and next work

- Data is in memory and disappears on restart. A multi-instance deployment needs a transactional database, a unique constraint on idempotency keys, and row locking or an atomic update.
- The HTTP adapter is intentionally plain text and minimal. Add JSON schemas, authentication, request size limits, structured errors, and integration tests before deployment.
- Expired hold records are removed lazily. Idempotency keys remain reserved, and the same key cannot create another hold after expiry. Add a documented retention policy.
- Stock changes are a setup method, not an authenticated API. Reducing stock below current commitments is not guarded; never call `stock` for live inventory changes.
- No load numbers are claimed. Benchmark with a documented machine, warm-up, request mix, and percentile latencies.

**Verification in this workspace:** source inspected; Java runtime present but `javac` absent, so compilation and tests are pending.
