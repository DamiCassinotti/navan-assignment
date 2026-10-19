# Plan — Task B: Travel transactions & invoice service

## Context

This is a greenfield take-home. The repo currently contains only the brief (`task-b-travel-inventory-invoice-CANDIDATE.md`) and `fixtures/task-b/*.json`. Navan asks for a small HTTP service in **Java + Spring Boot**, backed by a **real on-disk database**, that:

- accepts already-structured travel transactions (`flight` / `hotel` / `rail` / `navan_fee`) via a single `POST /transactions`
- enforces `external_id` uniqueness in the database (including under concurrent POSTs)
- creates **frozen snapshot** invoices from one or several transactions
- supports update/delete with the rule that any transaction already on an invoice can't be mutated or deleted (409)
- paginates list endpoints with a hard cap
- ships with automated tests covering **at least** the 10 scenarios in the brief

Goal: a reviewer-defendable service with clean seams (HTTP / persistence / identity / invoice snapshot) and strong test coverage. Not a one-class demo.

## Stack choice

- **Java 21**, **Spring Boot 3.x**, built with **Gradle wrapper** (`./gradlew`).
- **SQLite** via `org.xerial:sqlite-jdbc`. DB file lives at **`./data/navan.db`**; the service **auto-creates** the file and applies `schema.sql` on first boot. The file will be committed to version control after the service has been exercised end-to-end a few times so a reviewer can inspect it; `./data/` is **not** gitignored.
- Persistence via **jOOQ** with code generation driven off `src/main/resources/schema.sql` (the `nu.studer.jooq` Gradle plugin applies the schema to a build-time SQLite file, then generates typesafe records/DAOs). See the "Why not JPA?" aside below for why not JPA and why jOOQ earns its keep.
- **Schema management**: a single `src/main/resources/schema.sql` applied on every boot by Spring Boot's `spring.sql.init`, with `CREATE TABLE IF NOT EXISTS` so repeated boots are idempotent. No Flyway for this scope — the schema isn't versioned across environments and adding Flyway would be dead weight. **Trade-off:** `IF NOT EXISTS` means schema changes don't apply to an existing DB file; the local workflow is "delete `./data/navan.db` and restart", documented in README. If this ever ships beyond the take-home, Flyway (or Liquibase) is the first thing to add.
- **Jackson** for JSON. Store type-specific `metadata` as a JSON **string column**; taxes/fees/identity live in real columns and tables.
- Money: **`BigDecimal`**, stored as **`TEXT`** (via `toPlainString()` / `new BigDecimal(String)`), half-even rounding when we round. **Why TEXT, not `DECIMAL(19,4)`:** SQLite has no real decimal type — a `DECIMAL(19,4)` column has NUMERIC affinity and still stores values as `REAL` (double) when they look numeric, which silently loses scale and precision (`220.00` round-trips as `220.0`, and worse under arithmetic). Storing the canonical decimal string sidesteps that entirely; sorting/comparison needs are met at the application layer. If we moved to Postgres this flips back to native `NUMERIC(19,4)`.
- Primary keys: **UUID**, generated server-side (`UUID.randomUUID()`), physically stored as `BLOB(16)` (SQLite has no native UUID type; `BLOB(16)` is the closer-to-UUID choice over `TEXT`, keeps rows compact, and jOOQ handles the `UUID ↔ byte[16]` converter via a custom binding so Java sees a real `java.util.UUID`). **`external_id`** stays as `TEXT` — it's a client-supplied string (booking refs/PNRs), not a UUID.
- Tests: **JUnit 5** + **Mockito** for unit tests; Spring Boot Test + `MockMvc` for integration tests. Per-test temp SQLite file (`@TempDir`) in integration tests.

### Why not JPA (and would jOOQ work?)

Not fundamentally broken, but friction is real:

- **No official Hibernate dialect for SQLite.** You pull in a community dialect (`org.hibernate.community:hibernate-community-dialects` has one; the long-standing `com.github.gwenn:sqlite-dialect` is the other). Both work but are off the beaten path and have known gaps around sequence/identity handling, `BigDecimal` scale, and timestamp precision — exactly the areas this task cares about.
- **SQLite's dynamic typing** fights Hibernate's assumption that the DDL it generates round-trips cleanly. The usual outcome is "it works until you store a `DECIMAL(19,4)` and get `19.0000000000001` back." Easy to defend against if you know, surprising if you don't.
- **No `ALTER COLUMN`** in SQLite complicates `hbm2ddl=update`. The local workflow here is "delete the DB file and let `schema.sql` recreate it", at which point JPA's schema management is dead weight.
- **jOOQ fits this exercise.** The `nu.studer.jooq` Gradle plugin wires up schema-driven code generation in one block of `build.gradle` — the plugin applies `schema.sql` to a build-time SQLite file, jOOQ generates records/tables, and from then on all queries are typesafe. On a fresh project it's a 15-minute setup, not an hour. jOOQ speaks SQLite natively, keeps the SQL first-class (which is what the brief wants reviewed), and gives us a clean place to hang the `UUID ↔ BLOB(16)` converter.

So: jOOQ for queries, plain `schema.sql` for schema. Honest, small, defensible.

## Data model

Tables (all with `created_at`/`updated_at` where useful):

All generated `id` columns are `BLOB(16)` holding a UUID. `external_id` is `TEXT` (client-supplied string). All PKs/FKs are UUIDs; the lines below drop the type for brevity.

All money/rate columns are **`TEXT`** holding the `BigDecimal` canonical string (see "Money" above). All timestamp columns are `TEXT` holding ISO-8601 `Instant`.

- `transactions` — `id` (PK, UUID), `external_id TEXT` (**UNIQUE** index), `type`, `currency`, `occurred_at`, `total`, `metadata` (TEXT, JSON), `created_at`, `updated_at`.
- `tax_lines` — `id`, `transaction_id` (FK UUID, indexed, `ON DELETE CASCADE`), `name`, `rate` (nullable), `amount`.
- `fee_lines` — `id`, `transaction_id` (FK UUID, indexed, `ON DELETE CASCADE`), `name`, `amount`.
- `invoices` — `id`, `currency`, `grand_total`, **`tax_rollup`** (TEXT, JSON — cached), **`fee_rollup`** (TEXT, JSON — cached), `created_at`. Rollups computed once at snapshot write (POST / PATCH) and read back verbatim on GET.
- `invoice_lines` — `id`, `invoice_id` (FK UUID, indexed), `transaction_id` (UUID, indexed), `type`, `total`, `metadata_snapshot` (TEXT). `UNIQUE(invoice_id, transaction_id)` — dedupe enforced in the DB.
- `invoice_tax_lines` — `id`, `invoice_line_id` (FK UUID, indexed), `name`, `rate`, `amount`.
- `invoice_fee_lines` — `id`, `invoice_line_id` (FK UUID, indexed), `name`, `amount`.

SQLite does not enforce foreign keys by default — `PRAGMA foreign_keys = ON` is set per connection via Hikari's `connection-init-sql`.

Indexes called out in `ARCHITECTURE.md`:
- `UNIQUE(transactions.external_id)` — identity + concurrent-insert guard.
- `INDEX(invoice_lines.transaction_id)` — "is this tx on an invoice?" is a cheap lookup; critical on PATCH/DELETE.
- `INDEX(invoice_lines.invoice_id)`, `INDEX(tax_lines.transaction_id)`, `INDEX(fee_lines.transaction_id)` — snapshot and GET round-trip.

## Package layout

```
src/main/java/com/navan/assignment/
├── NavanApplication.java
├── web/
│   ├── TransactionController.java
│   ├── InvoiceController.java
│   ├── HealthController.java
│   ├── dto/
│   └── GlobalExceptionHandler.java
├── transaction/
│   ├── TransactionService.java
│   ├── TransactionRepository.java
│   ├── MetadataValidator.java
│   └── model/ Transaction, TaxLine, FeeLine
├── invoice/
│   ├── InvoiceService.java      # snapshot build + rollup computation on write
│   ├── InvoiceRepository.java
│   └── model/ Invoice, InvoiceLine, Rollup
└── common/
    ├── Money.java
    └── errors/ InvoicedTransactionException, MixedCurrencyException, NotFoundException, BadRequestException
```

## Endpoint behavior

- `POST /transactions` — validate shape by `type`; `navan_fee.fee_kind` must be in `{trip_fee, agent_call_fee}`; insert header + tax/fee lines atomically inside `@Transactional`. On `DataIntegrityViolationException` from `external_id` unique index, SELECT the existing row and return **200 OK with the existing resource** (idempotent duplicate handling; documented in README).
- `GET /transactions/{id}` — 404 if missing; round-trips `metadata`, `tax_lines`, `fee_lines`.
- `PATCH /transactions/{id}` — before writing, `EXISTS(SELECT 1 FROM invoice_lines WHERE transaction_id=?)` → **409**, no writes. Otherwise atomic replace of tax/fee lines inside one DB transaction.
- `DELETE /transactions/{id}` — see **Concurrency** section below for the race model.
- `POST /invoices` — accept `transaction_id` or `transaction_ids`. **Dedupe repeated ids** in the request to one snapshot line (documented). 404 if any missing; **409** on mixed currencies. One DB transaction: insert `invoices` row, insert `invoice_lines` + `invoice_tax_lines` + `invoice_fee_lines`, compute tax/fee rollups and `grand_total`, write them onto the `invoices` row in the same transaction.
- `GET /invoices/{id}` — read the invoice row, its lines, and the **cached** `tax_rollup`/`fee_rollup`/`grand_total` columns. No recomputation.
- `PATCH /invoices/{id}` — same body shape as create. One atomic write: delete old snapshot rows + rollup, re-insert and recompute. 404 if invoice or any id missing; 409 mixed currency, unchanged.
- `DELETE /invoices/{id}` — remove invoice + snapshot rows only; underlying transactions untouched.
- `GET /transactions`, `GET /invoices` — require `limit` (default 50) and `offset`; `limit > 100` → **400 Bad Request**.
- `GET /health` — trivial 200.

## Concurrency

- **`external_id` uniqueness:** DB unique index + catch `DataIntegrityViolationException`. No app-level locks.
- **Invoice-vs-DELETE race (user's call: write-first strategy, documented and defended).**
  - `POST /invoices` does the invoice-row insert and the `invoice_lines` INSERTs **before** returning. Those INSERTs sit inside a single DB transaction and reference `transaction_id` via FK.
  - `DELETE /transactions/{id}` runs inside its own transaction: it first `SELECT`s from `invoice_lines WHERE transaction_id=?`, and if any row is present → **409** and no delete. If none is present it deletes the transaction + its tax/fee lines.
  - Since SQLite serializes writes (one writer at a time), the two transactions can't interleave at the row level. The window where DELETE could "sneak in" is only between a client *starting* `POST /invoices` and the first `invoice_lines` INSERT landing — and in our code that INSERT is the very first write of the invoice transaction. So the invoice write **wins the race** in practice: either DELETE gets there first (and the invoice POST then fails its own FK/existence check → 404), or the invoice line INSERT lands first (and DELETE sees the row and returns 409).
  - **Known residual risk (documented in `ARCHITECTURE.md`):** on a backend with less serial write behavior (Postgres under `READ COMMITTED`), two concurrent transactions could both pass their respective checks and both commit; a user who deletes exactly as an invoice is being created could race through. The clean fix is a pessimistic row lock on `transactions.id` (`SELECT ... FOR UPDATE`) at the start of both DELETE and `POST /invoices`. I'm not doing that today — SQLite's one-writer-at-a-time semantics already make the race effectively unreachable, and adding `FOR UPDATE` doesn't translate to SQLite anyway. Flagged as the first thing to change if we moved to Postgres.
- **PATCH races:** each handler runs in a single DB transaction; the invoiced-check happens inside the same transaction as the write. Last-write-wins on PATCH content is acceptable per the brief.

## Tests

Two layers, Mockito for unit tests, Spring Boot Test + MockMvc for integration. Fixtures loaded from `fixtures/task-b/seed-payloads.json`.

### Naming convention

- **`*UnitTests`** — pure JUnit + Mockito, no Spring context.
- **`*IntegrationTests`** — `@SpringBootTest(webEnvironment=RANDOM_PORT)` with a per-test temp SQLite DB.

### Structure

One test file **per production class** (service / repository / controller), with the 10 required scenarios distributed across them as `@Nested` inner classes:

```
TransactionServiceUnitTests
  @Nested CreateTransaction          // metadata validator, fee_kind whitelist, mocked repo
  @Nested DuplicateHandling          // mocked DataIntegrityViolationException → returns existing
  @Nested InvoicedGuard              // PATCH/DELETE throws when invoice_lines exists

InvoiceServiceUnitTests
  @Nested SnapshotBuild              // rollup math on mocked tx data
  @Nested MixedCurrency              // throws on mixed currencies
  @Nested DuplicateIdDedupe          // repeated id collapses to one line

TransactionControllerIntegrationTests
  @Nested Create                      // (brief req #1) POST all five types from seeds; GET round-trip
  @Nested DuplicateExternalId         // (brief req #2) 200 OK + same id; count=1
  @Nested Pagination                  // (brief req #10) default 50; limit=1000 → 400
  @Nested PatchWhenInvoiced           // (brief req #7) 409, totals unchanged
  @Nested DeleteWhenInvoiced          // (brief req #8) 409, row still GETs
  @Nested ConcurrentDuplicateCreate   // (brief req #6) ExecutorService + CountDownLatch, one row

InvoiceControllerIntegrationTests
  @Nested TripInvoice                 // (brief req #3) grand_total 685.70
  @Nested Snapshot                    // (brief req #4) mutate flight → invoice still 220.00
                                      //   via direct repository update that bypasses the service guard,
                                      //   documented in a comment on the test
  @Nested MixedCurrency               // (brief req #5) EUR + USD → 409
  @Nested DeleteInvoice               // (brief req #9) invoice gone, transactions still GET

TransactionRepositoryIntegrationTests
  @Nested CrudRoundtrip
  @Nested UniqueConstraint           // raw DB-level duplicate insert → DataIntegrityViolationException

InvoiceRepositoryIntegrationTests
  @Nested SnapshotRoundtrip
  @Nested RollupPersistence          // tax_rollup/fee_rollup read back exactly as written
```

Run with `./gradlew test`.

## Files to produce

- `build.gradle(.kts)`, `settings.gradle(.kts)`, `./gradlew`, `./gradlew.bat`, `gradle/wrapper/*`.
- `src/main/resources/application.yml` — SQLite datasource (with `PRAGMA foreign_keys = ON` init), `spring.sql.init` pointing at `schema.sql`, server port.
- `src/main/resources/schema.sql` — the schema above, applied idempotently on every boot.
- All Java sources under the package layout above.
- Test sources per the convention above.
- `data/navan.db` — committed; service auto-creates and migrates if absent.
- `README.md` — one command to run (`./gradlew bootRun`), one command for tests (`./gradlew test`), curl snippets for every endpoint including the duplicate-POST curl the brief asks for.
- `ARCHITECTURE.md` — one page: identity (unique index + conflict catch), concurrent POST handling, snapshot design (why copy columns + metadata JSON + cached rollups), data-model rationale (real tables for tax/fee lines, JSON column for type-specific metadata we don't need to query), **High volume** (indexes above, hard-cap pagination, what fails first — unbounded list, then PATCH contention under hot external_ids), and the **DELETE-vs-invoice race writeup** including the residual risk and the Postgres `FOR UPDATE` fix I'd apply.
- `.gitignore` — add `.gradle/`, `build/`, `.idea/`, `.vscode/`, but **not** `data/`.

## Scope rules

- Commits authored under my git identity, no "Co-Authored-By: Claude", no "Generated with Claude Code" footer.
- Code comments only where a non-obvious *why* needs explaining; no doc blocks, no section labels, no PR/thesis references.
- README/ARCHITECTURE kept plain and short.

## Verification

1. `./gradlew clean build` — compiles, runs all tests, `schema.sql` applies cleanly against a fresh SQLite file.
2. `./gradlew bootRun`, then manually:
   - Run the duplicate-POST curl from the README — assert `count=1` via `GET /transactions?limit=100`.
   - POST all 5 seeds, POST invoice with those ids, GET invoice → grand_total `685.70`.
   - PATCH one of the invoiced transactions → 409.
3. `GET /transactions?limit=1000` → 400.
4. `GET /health` → 200.

## Out of scope

Receipts, OCR, auto-itemize, auth, UI, PDF, payments, Redis/Kafka, Prometheus — per the brief.
