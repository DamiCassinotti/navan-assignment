# Architecture

One page on how the service is wired, why the schema looks the way it does, and the few places where the design has sharp edges.

## Layout

```
web/          HTTP controllers + DTOs + a single @RestControllerAdvice for error mapping
transaction/  TransactionService / Repository / MetadataValidator + domain models
invoice/      InvoiceService / Repository + snapshot build and rollup computation
common/       Money helpers, typed error classes
```

Controllers are thin: parse DTO, call service, map result. Services own all the business rules and run every write inside a single `@Transactional`. Repositories are jOOQ queries against the schema in `src/main/resources/schema.sql`.

## Identity and the unique-index strategy

`transactions.external_id` is client-supplied (booking refs, PNRs). Uniqueness is enforced in the DB:

```sql
CREATE UNIQUE INDEX ux_transactions_external_id ON transactions(external_id);
```

The service does **not** pre-check `SELECT ... WHERE external_id = ?` before inserting. That check-then-insert pattern is racy — two concurrent POSTs with the same `external_id` can both pass the check and both insert, and a single-row application lock would just move the bottleneck without fixing it.

Instead, `POST /transactions` writes blind. If the unique index rejects the insert, jOOQ surfaces a `DataIntegrityViolationException`; the service catches it, selects the existing row by `external_id`, and returns **200 OK** with that row (the original — the duplicate payload is discarded). First writer wins, idempotent for the client, no app-level locking. The brief's concurrent-duplicate test (`ConcurrentDuplicateCreate`) exercises this with an `ExecutorService` + `CountDownLatch` and asserts exactly one row lands.

## Snapshot design

Invoices are **frozen** at write time. A `PATCH /transactions/{id}` that lands after the invoice is created must not change the invoice's totals or line details. The schema reflects that:

- `invoice_lines` has its own copies of `type`, `currency`, `occurred_at`, `total`, and a `metadata_snapshot` TEXT column. These are copied from the transaction at `POST /invoices` time.
- `invoice_tax_lines` and `invoice_fee_lines` are copies of the transaction's tax/fee lines at that moment, hanging off `invoice_line_id` rather than `transaction_id`.
- `invoices` stores `grand_total`, `tax_rollup` (JSON) and `fee_rollup` (JSON) as cached columns, computed once at write and read verbatim on GET. No recomputation on read.

Why columns **and** a JSON metadata snapshot:

- The columns we actually filter, aggregate, or report on — `total`, `currency`, `occurred_at`, `type` — are first-class columns so queries stay typesafe and indexable.
- Per-type metadata (flight origin/destination/fare, hotel nights, rail legs, navan_fee kind) is a shape that varies by type and that we don't query. Modeling each per-type field as its own column would mean a wide, mostly-null table and a schema change every time a new transaction type appears. Storing it as a JSON string column keeps the snapshot complete and lets GET round-trip the exact shape the client posted, without the service needing to understand it.
- Rollups are cached because the brief's `GET /invoices/{id}` must return `grand_total` + `tax_rollup` + `fee_rollup`, and recomputing on read would mean re-reading the snapshot lines on every GET for no gain. Computing once at write (POST or PATCH) and storing the result makes reads a single-row fetch.

The invoiced-transaction guard lives on top of this: `PATCH` / `DELETE /transactions/{id}` first run `EXISTS(SELECT 1 FROM invoice_lines WHERE transaction_id = ?)`; if any row exists, the request is rejected with 409. The snapshot columns are untouched regardless, so even a direct repository mutation that bypasses the guard (exercised in the `Snapshot` test) leaves the invoice totals intact.

## Indexes

Called out explicitly because each one earns its place:

- `UNIQUE(transactions.external_id)` — identity + concurrent-insert guard (see above).
- `INDEX(invoice_lines.transaction_id)` — the invoiced-check on `PATCH` / `DELETE /transactions` is a point lookup against this index; also used by the DELETE-vs-invoice race check below.
- `INDEX(invoice_lines.invoice_id)` — snapshot round-trip on `GET /invoices/{id}`.
- `INDEX(tax_lines.transaction_id)`, `INDEX(fee_lines.transaction_id)` — snapshot build on `POST /invoices` reads all tax/fee lines for each referenced transaction.
- `INDEX(invoice_tax_lines.invoice_line_id)`, `INDEX(invoice_fee_lines.invoice_line_id)` — invoice GET round-trip reads tax/fee snapshot lines per invoice line.

`UNIQUE(invoice_lines.invoice_id, transaction_id)` dedupes repeated ids inside a single invoice at the DB level, backing up the service-level dedupe on request.

## DELETE-vs-invoice race

The nasty one. Two users act at the same time: user A `POST /invoices` with transaction T, user B `DELETE /transactions/T`. We need "T is either on the invoice and undeletable, or deleted and not on the invoice" — never both, never neither.

**Strategy: write-first, with the invoice as the winner.**

- `POST /invoices` runs in one DB transaction. Its very first write is `INSERT INTO invoice_lines ... (transaction_id = T, ...)`. If T no longer exists, the FK on `invoice_lines.transaction_id` rejects the insert and the invoice POST fails (`404` is returned via a pre-check `SELECT`, or FK violation if the delete lands between the pre-check and the insert — same outcome from the client's perspective).
- `DELETE /transactions/{T}` runs in one DB transaction. Its first read is `SELECT 1 FROM invoice_lines WHERE transaction_id = T`. If any row exists, 409 and no delete. Otherwise it deletes the transaction (which cascades to tax/fee lines).

**Why SQLite makes this safe in practice.** SQLite serializes writes — only one writer transaction at a time. The two transactions above can't interleave at the row level. Either the `invoice_lines` INSERT lands first (DELETE then sees the row and returns 409), or the DELETE commits first (POST then sees T is gone and returns 404 / FK violation). There's no third branch.

### Residual risk (and the Postgres fix)

**This is not safe on a database with less serial write behavior.** Under Postgres with the default `READ COMMITTED` isolation, two concurrent transactions can each take their own MVCC snapshot:

1. User B's DELETE snapshot sees no `invoice_lines` row for T. It proceeds to delete T.
2. User A's POST snapshot sees T still exists. It proceeds to insert the `invoice_lines` row.
3. Both commit. The invoice ends up with an `invoice_lines` row whose `transaction_id` points at a now-gone transaction (if the FK is `NOT ENFORCED` or `DEFERRABLE INITIALLY DEFERRED`), or one of the two gets an FK violation at commit — but it's non-deterministic which one.

Serializable isolation closes the gap but is costly and adds retries. The clean, local fix is a pessimistic row lock on `transactions.id` taken at the start of both operations:

```sql
-- POST /invoices (Postgres)
SELECT id FROM transactions WHERE id = :txId FOR UPDATE;
-- ... now the invoice_lines INSERT

-- DELETE /transactions/{id} (Postgres)
SELECT id FROM transactions WHERE id = :txId FOR UPDATE;
-- ... now the EXISTS check against invoice_lines, then the DELETE
```

With both paths locking the same row before deciding, whichever transaction grabs the lock first forces the other to wait and re-read; the loser then sees the committed state (either T is gone, or an `invoice_lines` row exists) and takes the correct branch.

I did **not** add `FOR UPDATE` to this build. SQLite doesn't honor it — the dialect accepts the clause but the engine's one-writer semantics already give us the same guarantee for free, so adding it would be cargo-culting. It's flagged here as the single-line change to make if this service were ever moved onto Postgres (or any other backend that allows concurrent writers to proceed past a check).

## High-volume behavior

List endpoints are hard-capped: `limit` default 50, `limit > 100` → 400. There's no cursor pagination because the brief doesn't require it; for a dataset where new rows keep landing, swapping offset for a keyset cursor on `created_at, id` is the next move.

The thing that fails first under load is **not** the invoice path — the `invoice_lines.transaction_id` index keeps the invoiced-check O(1), and the FK + unique indexes do all the heavy lifting on writes. The thing that fails first is an unbounded list query (hence the hard cap) and PATCH contention on a hot `external_id` where many clients retry the same duplicate — the retries all do the duplicate-select path, which is cheap, but there's no backpressure on the client. In practice, the SQLite file lock is the first real bottleneck; moving to Postgres (and taking the `FOR UPDATE` fix above with it) is what unblocks real concurrency.
