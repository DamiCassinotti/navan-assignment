# navan-assignment

Take-home: travel transactions + invoice service. See [`task-b-travel-inventory-invoice-CANDIDATE.md`](task-b-travel-inventory-invoice-CANDIDATE.md) for the brief and [`PLAN.md`](PLAN.md) for the approach.

## Stack

- Java 21, Spring Boot 3.3
- SQLite (file at `./data/navan.db`, auto-created on first boot)
- jOOQ 3.19 for queries, codegen off `src/main/resources/schema.sql`
- Gradle wrapper

## Run

```bash
./gradlew bootRun       # starts the service on :8080
./gradlew test          # runs unit + integration tests
./gradlew build
```

## Endpoints

- `POST /transactions` — create a transaction. `201` on insert, `200` on duplicate `external_id` (returns the existing row).
- `GET /transactions/{id}` — fetch by id, `404` if missing.
- `PATCH /transactions/{id}`, `DELETE /transactions/{id}`, `GET /transactions` (paginated).
- `POST /invoices` — snapshot one or more transactions. Accepts `transaction_id` or `transaction_ids`; repeated ids collapse to one line. `404` if any id is missing, `409` on mixed currencies. Returns cached `tax_rollup` / `fee_rollup` / `grand_total`.
- `GET /invoices/{id}` — fetch the snapshot and cached rollups verbatim (no recomputation), `404` if missing.
- `GET /invoices` — paginated (`limit` default 50, max 100; `offset` default 0).

### curl

```bash
# Flight (fresh)
curl -s -X POST localhost:8080/transactions -H 'content-type: application/json' \
  -d @fixtures/task-b/seed-payloads.json  # (post an individual fixture; the file holds multiple)

# Duplicate POST — same external_id → 200 OK with the original row
```

See `fixtures/task-b/seed-payloads.json` for one payload per type (`flight`, `hotel`, `rail`, two `navan_fee`s) plus a `duplicate_flight` entry to exercise idempotency.

## Schema

Schema lives in `src/main/resources/schema.sql` and is applied idempotently (`CREATE TABLE IF NOT EXISTS`) on every boot via Spring Boot's SQL init. No migration tool.

**When the schema changes**, delete `./data/navan.db` and restart — `IF NOT EXISTS` will not alter existing tables. Tests use a per-run temp DB file, so they're unaffected.

Foreign keys are enabled per connection via Hikari's `connection-init-sql: "PRAGMA foreign_keys = ON"` — SQLite does not enforce FKs by default.

## Tests

- `*UnitTests` — plain JUnit 5 + Mockito, no Spring context.
- `*IntegrationTests` — `@SpringBootTest` + MockMvc with a per-test-class temp SQLite file.

Run `./gradlew test`.
