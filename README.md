# Idempotent money transfers

A small, sequential demo of [REST API idempotency](https://restfulapi.net/idempotent-rest-apis/) using Java 25 LTS, Spring Boot 4.1.1, Hibernate / Spring Data JPA, PostgreSQL 18, and Docker Compose.

The lesson: if a client loses the response to a transfer, retrying with the same idempotency key returns the original result without transferring money again. **Send requests one at a time, waiting for each response.** Concurrent-request handling is outside this demo's scope.

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

This returns **409 Conflict**, with no balance change.

| Request | Result |
| --- | --- |
| New key | Process the transfer and save its result |
| Same key, same payload | Replay the saved result; balances stay unchanged |
| Same key, different payload | Return 409; balances stay unchanged |
| Different key, same payload | Create another transfer |

Generate one UUID key per intended transfer and reuse it for retries. Spring's required `@RequestHeader("Idempotency-Key") UUID key` binding returns 400 for missing or malformed values. Keys are saved as canonical strings in the database.

## How it works

1. Look up the idempotency key through a JPA repository.
2. If it exists, compare the account IDs and amount. Replay the saved result or reject a changed payload.
3. Otherwise, load the accounts, debit the source, credit the destination, and save the result.

A single `@Transactional` method commits the balance changes and saved result together. Hibernate dirty checking persists the account changes. Flyway owns the schema; Hibernate validates it. There are no custom locks, native runtime queries, or JDBC result-set mappings.

Amounts are positive integer USD cents. Fractional amounts and unknown JSON fields return 400 without consuming the key. Unknown accounts return 404; same-account transfers, insufficient funds, and the balance limit return 422. Business failures are saved and replayed for the same key, even if balances later change. Amounts and balances are capped at 1,000,000,000,000 cents.

## Tests

Run controller binding and validation tests without PostgreSQL:

```sh
mvn test
```

Run the sequential integration tests against a disposable PostgreSQL database:

```sh
docker compose up -d --wait
RUN_DB_TESTS=true mvn test
```

Integration tests reset demo data and cover replay, changed payloads, new keys, saved business failures, HTTP response replay, and strict JSON input. They are skipped unless `RUN_DB_TESTS=true`.

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

- **Concurrency and race conditions:** the lookup-then-save flow assumes sequential requests. Simultaneous requests can race, fail, or overwrite balances. A unique key constraint alone does not make this flow safe under concurrency. Account locks, duplicate-insert recovery, transaction retries, and multi-instance coordination belong in a separate lesson.
- **Production payments:** external payment providers, double-entry accounting, reconciliation, currencies, and distributed side effects are not implemented. Money movement happens inside one database.
- **Authentication and ownership:** accounts have no access checks; idempotency keys are global rather than scoped to users.
- **Key lifecycle:** results are retained indefinitely; expiration, cleanup, and retention guarantees are not implemented.

GET reads state without changing it. POST remains a non-idempotent HTTP method; this endpoint makes sequential retries safe through its idempotency-key contract. Transfers are immutable, so there are no PUT, PATCH, or DELETE endpoints.
