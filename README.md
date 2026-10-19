# navan-assignment

Take-home: travel transactions + invoice service. See [`task-b-travel-inventory-invoice-CANDIDATE.md`](task-b-travel-inventory-invoice-CANDIDATE.md) for the brief, [`PLAN.md`](PLAN.md) for the approach, and [`ARCHITECTURE.md`](ARCHITECTURE.md) for identity / snapshot / concurrency details.

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
- `PATCH /invoices/{id}` — re-snapshot atomically (same body as `POST`). `404` if missing, `409` on mixed currencies.
- `DELETE /invoices/{id}` — remove the invoice + snapshot rows; underlying transactions untouched. `404` if missing.
- `PATCH /transactions/{id}` and `DELETE /transactions/{id}` return `409` while the transaction is on any invoice.
- `GET /health` — `200 {"status":"ok"}`.

### curl

Full snippets (every endpoint, happy path + rejections) live in [`curls.md`](curls.md). Fixtures are in `fixtures/task-b/seed-payloads.json` (one payload per type: `flight`, `hotel`, `rail`, two `navan_fee`s).

```bash
# Health
curl -s localhost:8080/health

# Create a transaction (flight)
curl -s -X POST localhost:8080/transactions -H 'content-type: application/json' \
  -d "$(jq '.flight' fixtures/task-b/seed-payloads.json)"
```

Duplicate POST demo — same `external_id` → `200 OK` with the ORIGINAL row (the duplicate payload is discarded; see [`ARCHITECTURE.md`](ARCHITECTURE.md) for the identity strategy):

```bash
curl -s -X POST localhost:8080/transactions -H 'content-type: application/json' \
  -d '{"type":"flight","external_id":"flight-TXL-LHR-2026-03-11","currency":"EUR",
       "occurred_at":"2026-03-11T06:40:00Z","total":220.00,
       "tax_lines":[{"name":"Airport tax","amount":15.00}],
       "fee_lines":[{"name":"YQ","amount":25.00}],
       "metadata":{"origin":"TXL","destination":"LHR","fare":180.0}}'

curl -s -X POST localhost:8080/transactions -H 'content-type: application/json' \
  -d '{"type":"flight","external_id":"flight-TXL-LHR-2026-03-11","currency":"EUR",
       "occurred_at":"2026-03-11T06:40:00Z","total":999.99,
       "tax_lines":[],"fee_lines":[],"metadata":{"origin":"TXL","destination":"LHR"}}'

# Confirm one row — expect 1
curl -s 'localhost:8080/transactions?limit=100' \
  | jq '.items | map(select(.external_id=="flight-TXL-LHR-2026-03-11")) | length'
```

Transaction read / update / delete / list:

```bash
curl -s         localhost:8080/transactions/$ID
curl -s -X PATCH  localhost:8080/transactions/$ID -H 'content-type: application/json' -d '{...}'
curl -i -X DELETE localhost:8080/transactions/$ID
curl -s 'localhost:8080/transactions?limit=10&offset=0'   # limit > 100 → 400
```

Invoices:

```bash
# Create — accepts transaction_id or transaction_ids
curl -s -X POST localhost:8080/invoices -H 'content-type: application/json' \
  -d "{\"transaction_ids\":[\"$FLIGHT\",\"$HOTEL\",\"$RAIL\",\"$TRIP\",\"$CALL\"]}"

# Read — cached rollups + grand_total verbatim
curl -s         localhost:8080/invoices/$INVOICE_ID
# Update — same body as POST, re-snapshots atomically
curl -s -X PATCH  localhost:8080/invoices/$INVOICE_ID -H 'content-type: application/json' \
  -d "{\"transaction_ids\":[\"$HOTEL\",\"$RAIL\"]}"
# Delete — underlying transactions untouched
curl -i -X DELETE localhost:8080/invoices/$INVOICE_ID
# List — paginated
curl -s 'localhost:8080/invoices?limit=10&offset=0'
```

## Schema

Schema lives in `src/main/resources/schema.sql` and is applied idempotently (`CREATE TABLE IF NOT EXISTS`) on every boot via Spring Boot's SQL init. No migration tool.

**When the schema changes**, delete `./data/navan.db` and restart — `IF NOT EXISTS` will not alter existing tables. Tests use a per-run temp DB file, so they're unaffected.

Foreign keys are enabled per connection via Hikari's `connection-init-sql: "PRAGMA foreign_keys = ON"` — SQLite does not enforce FKs by default.

## Tests

- `*UnitTests` — plain JUnit 5 + Mockito, no Spring context.
- `*IntegrationTests` — `@SpringBootTest` + MockMvc with a per-test-class temp SQLite file.

Run `./gradlew test`.
