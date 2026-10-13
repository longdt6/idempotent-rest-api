# Idempotent money transfers

A small demo of [REST API idempotency](https://restfulapi.net/idempotent-rest-apis/) using Java 25 LTS, Spring Boot 4.1.1, Hibernate / Spring Data JPA, PostgreSQL 18, and Docker Compose.

The lesson: if a client loses the response to a transfer, retrying with the same idempotency key returns the original result without transferring money again. The flow follows the article’s diagram: insert `PROCESSING`, perform the operation, then save the completed response.

## Run

Install JDK 25 and Maven 3.9+, and start Docker. Check that `mvn -version` uses JDK 25.

```sh
docker compose up -d --wait
mvn spring-boot:run
```

Spring Boot runs locally on the JVM; Compose runs PostgreSQL. DevTools restarts the app when compiled classes change. Rebuild in your IDE or run `mvn compile` after editing Java files.

On a fresh database, account 1 has 100000 USD cents ($1,000) and account 2 has 50000 cents ($500).

## Try idempotency

Read the initial balances:

```sh
curl -s localhost:8080/accounts
```

Create a $25 transfer:

```sh
curl -i localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000' \
  -d '{"fromAccountId":1,"toAccountId":2,"amountCents":2500}'
```

Run that exact command again after it completes. Both responses return **201**, the same transfer ID, body, and `Location`. The retry has `Idempotency-Replayed: true`. Read `/accounts` again: the balances are **97500** and **52500**, because only one transfer occurred. You can also GET the returned `Location` to read the transfer.

Now reuse the key with a different amount:

```sh
curl -i localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000' \
  -d '{"fromAccountId":1,"toAccountId":2,"amountCents":3000}'
```

This returns **422 Unprocessable Content**, with no balance change.

| Request | Result |
| --- | --- |
| New key | Insert PROCESSING, transfer money, then save the completed result |
| Same key, same payload | Replay the saved result; balances stay unchanged |
| Same key, different method/path/payload | Return 422; balances stay unchanged |
| Same key, still processing | Return 409 with Retry-After: 1 |
| PROCESSING for more than 30 seconds | Claim a new ownership token and retry the operation |
| Different key, same payload | Create another transfer |

Generate one UUID key per intended transfer and reuse it for retries. Spring's required `@RequestHeader("Idempotency-Key") UUID key` binding returns 400 for missing or malformed values. Keys are saved as canonical strings in the database.

## How it works

1. `TransferClaimService` inserts a `PROCESSING` entity in a separate transaction and commits it before business logic. The unique primary key decides which request owns a new key.
2. A duplicate insert rolls back. In a new transaction, compare the stored method, path, and parsed payload: return 422 for a mismatch, replay a completed response, or return 409 for an active request.
3. A request processing for more than 30 seconds can be taken over. A new ownership token and Hibernate `@Version` coordinate that change.
4. `TransferProcessor` locks only that request record, verifies ownership, moves the money, and marks the response `COMPLETED` in one transaction. A former owner cannot execute after takeover. A takeover racing an already-running transaction waits for its row lock and loses the version check if that transaction completed.

This uses Hibernate INSERT plus unique-constraint handling instead of the diagram's PostgreSQL `ON CONFLICT DO NOTHING`. The result is the same key-claim decision without native runtime SQL. Spring Data must use `persist`, not `merge`, for an assigned new key; the entity implements `Persistable` for this reason. There is no global mutex.

Business failures are completed responses and are replayed. Unexpected exceptions roll back the balance changes, then delete only the failing worker's own processing claim so the client can retry. A process crash leaves a claim available for takeover after 30 seconds. Flyway manages schema changes, including backfilling earlier results as `COMPLETED` in V4.

Request identity is `POST /transfers` plus parsed account IDs and cents. JSON whitespace and property order do not create a different request. The required UUID header remains unquoted, as in the existing API; the article discusses quoted draft header syntax separately.

## See the processing branch

Start the app with a short demonstration delay:

```sh
DEMO_PROCESSING_DELAY_MS=2000 mvn spring-boot:run
```

Using a fresh UUID, send the transfer in one terminal. While it is delayed, send the same key and payload from a second terminal: it returns **409** with `Retry-After: 1`. After the first request completes, retry once more to get the original **201** response. The delay runs before money movement and defaults to zero. To observe `PROCESSING` directly during a longer demo, set the delay to 10000 and inspect `transfer_requests` in PostgreSQL.

Amounts are positive integer USD cents. Fractional amounts and unknown JSON fields return 400 without consuming the key. Unknown accounts return 404; same-account transfers, insufficient funds, and the balance limit return 422. Business failures are saved and replayed for the same key, even if balances later change. Amounts and balances are capped at 1,000,000,000,000 cents.

## Tests

Run controller, claim-decision, and validation tests without PostgreSQL:

```sh
mvn test
```

Run the integration tests against a disposable PostgreSQL database:

```sh
docker compose up -d --wait
RUN_DB_TESTS=true mvn test
```

Integration tests reset demo data and cover replay, changed payloads, new keys, processing responses, expired claims, ownership checks, saved business failures, HTTP response replay, and strict JSON input. They are skipped unless `RUN_DB_TESTS=true`.

## Configuration and reset

Override `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, or `PORT` as needed. PostgreSQL binds to localhost. `docker compose down` keeps the data volume; balances and keys survive restarts.

To erase demo data and reseed on the next app start:

```sh
docker compose down -v
docker compose up -d --wait
```

Build the JVM jar with `mvn package`, then run `java -jar target/idempotent-transfers-0.0.1-SNAPSHOT.jar`.

For databases created by the earlier demo, migration V3 removes the old mutex table. V2 stays unchanged to preserve Flyway migration history.

## Out of scope

- **Concurrent transfers with different keys:** only request ownership is coordinated. Account locking and lost-update/overdraft protection across different keys are not implemented; use sequential independent transfers for this learning demo.
- **General-purpose middleware:** method and path are fixed to POST /transfers. A reusable idempotency component across arbitrary endpoints is outside scope.
- **Production payments:** external payment providers, double-entry accounting, reconciliation, currencies, and distributed side effects are not implemented. Money movement happens inside one database.
- **Authentication and ownership:** accounts have no access checks; idempotency keys are global rather than scoped to users.
- **Key lifecycle:** results are retained indefinitely; expiration, cleanup, and retention guarantees are not implemented.

GET reads state without changing it. POST remains a non-idempotent HTTP method; this endpoint recognizes retries through its idempotency-key contract. Transfers are immutable, so there are no PUT, PATCH, or DELETE endpoints.
