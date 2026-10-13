# Idempotent money transfers

Minimal learning demo based on [Idempotent REST APIs](https://restfulapi.net/idempotent-rest-apis/): Spring Boot 4.1.1, Hibernate with Spring Data JPA, Java 25 LTS, Maven, PostgreSQL 18, and Docker Compose. Run Spring Boot locally on the JVM for quick iteration; Compose runs only PostgreSQL.

## Run

Install JDK 25 and Maven 3.9+, and start Docker. Confirm `mvn -version` uses JDK 25.

```sh
docker compose up -d --wait
mvn spring-boot:run
```

DevTools restarts the application when compiled classes change. Rebuild in your IDE or run `mvn compile` after editing Java files.

Accounts 1 and 2 start with 100000 and 50000 USD cents ($1,000 and $500). Each amount is an integer number of cents. Fractional amounts and unknown JSON fields are rejected with 400. There is no external payment provider.

```sh
curl -s localhost:8080/accounts
curl -i localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: payment-001' \
  -d '{"fromAccountId":1,"toAccountId":2,"amountCents":2500}'
```

Repeat the POST exactly: both responses have status 201, the same transfer ID, body, and Location. The retry has `Idempotency-Replayed: true`. Account 1 is debited only once. Follow the returned Location with GET to read the transfer.

Reuse `payment-001` with `amountCents` changed to 3000: the server returns 409 and balances stay unchanged. A new key represents a new transfer, even if the payload is identical.

## How it works

1. Acquire a JPA pessimistic write lock on a shared `transfer_mutex` row before looking up the idempotency key. This prevents two transactions from simultaneously creating the same missing key, including requests handled by different application instances.
2. Compare the account IDs and amount against the saved entity. A changed payload returns 409; a completed request replays its saved result.
3. Lock the account entities in ascending ID order, then debit and credit them. Hibernate dirty checking saves balance changes automatically.
4. Save the transfer result through a Spring Data JPA repository. Both balances and the result commit in one transaction. A crash before commit rolls everything back; a lost response after commit can be retried safely.

For this minimal demo, the shared lock deliberately serializes **all transfers**, including different keys. This trades throughput for a simple implementation without database-specific runtime queries. Repositories use derived queries and JPA locks; Java code contains no JDBC, native SQL, or result-set mapping. Hibernate chooses the database dialect. Changing databases still requires the appropriate driver and compatible Flyway migrations; the existing UUID and timestamp migration targets PostgreSQL.

Flyway creates the schema; Hibernate validates it at startup. Open Session in View is disabled, and service methods return DTOs rather than exposing managed entities.

Validation errors (400) do not consume the key. Business failures are saved and replayed: unknown account (404), same source/destination (422), insufficient funds (422), or destination balance limit (422). Balances and amounts are capped at 1,000,000,000,000 cents. Database/system failures roll back the request and can be retried with the same key.

GET reads state without changing it. POST achieves safe retries through the key contract; this does not make POST intrinsically idempotent. Transfers are immutable, so this demo does not expose PUT, PATCH, or DELETE for payments.

## Tests

Run validation and controller tests without PostgreSQL:

```sh
mvn test
```

The integration tests use real PostgreSQL and cover HTTP response replay, strict JSON input, payload mismatch, concurrent duplicate requests, concurrent different keys, opposite-direction transfers, conflicting concurrent payloads, and remembered business failures. They reset demo data, so run them against a disposable database:

```sh
docker compose up -d --wait
RUN_DB_TESTS=true mvn test
```

Without `RUN_DB_TESTS=true`, only database tests are skipped; validation and controller tests still run. `mvn package` builds the executable JVM jar; run it with `java -jar target/idempotent-transfers-0.0.1-SNAPSHOT.jar`.

## Configuration and reset

Override `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, or `PORT` as needed. Compose binds PostgreSQL to localhost. Stop it with `docker compose down`; balances and keys persist in the volume. To erase all demo data and reseed on the next start:

```sh
docker compose down -v
docker compose up -d --wait
```

This is a single-currency learning application with no authentication. Keys are global and retained indefinitely; a real multi-user service needs ownership checks, a tenant-scoped key policy, and an explicit retention contract. All money movement here occurs inside one PostgreSQL database.
