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

**Note on the catch-and-select pattern vs Postgres.** On SQLite the violation surfaces at `execute()` time and the connection is left usable, so the follow-up `SELECT` by `external_id` just works. On Postgres the same pattern needs care: once a statement raises inside a transaction, the whole transaction block is aborted (`current transaction is aborted, commands ignored until end of transaction block`) and the follow-up SELECT fails. The portable rewrite is `INSERT ... ON CONFLICT (external_id) DO NOTHING RETURNING id`, then a SELECT by `external_id` only if the insert returned no row — or a savepoint around the insert. I didn't swap to `ON CONFLICT` here because the current catch is clear and SQLite handles it cleanly; it's flagged as the exact change to make alongside the `FOR UPDATE` fix if this moved to Postgres.

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

## Tables and models

**Taxes vs fees.** Two separate child tables (`tax_lines` / `fee_lines`), each a list of `{name, amount}` rows pointed at a transaction, with an optional `rate` on tax lines. They are not mashed into a single "extras" column, because the invoice rollup groups them differently — tax rollup is `(name, rate) → sum(amount)`, fee rollup is `name → sum(amount)` — and that distinction is a real business rule (VAT at 7% is one line in the rollup; two "YQ" surcharges from two carriers are one line too, but a VAT and a YQ with the same amount are not). Mirrored one-to-one onto `invoice_tax_lines` / `invoice_fee_lines` for the snapshot.

**Type-specific data.** All four transaction types share the `transactions` header (identity, currency, totals, timestamps) plus the same tax/fee child tables. Per-type payload lives in a single `metadata TEXT` JSON column:

- **flight** — `origin`, `destination`, `fare`, and a `coupons[]` array with `flight_number`, optional `carrier`, `from`, `to`, `departure`, `arrival`. Round-trips carry more than one coupon.
- **hotel** — `name`, `address{street, city, country}`, `check_in`, `check_out`, `nights`, `room_rate_per_night`, optional `extras`.
- **rail** — `legs[]` with `origin`, `destination`, `departure`, `arrival`, optional `train_number`/`class`. A journey with a change is two legs.
- **navan_fee** — `fee_kind` ∈ {`trip_fee`, `agent_call_fee`}, optional `description` and `related_external_id`.

Why JSON and not per-type child tables (coupons / legs / addresses):

- Nothing in the API filters, joins, or aggregates on these fields — they're round-tripped on read, snapshotted on invoice, and otherwise opaque. A column we never query doesn't earn an index or a schema.
- Per-type columns would be a wide, mostly-null `transactions` table and a schema change every time a new type appears (the brief already hints at more Navan-owned charge kinds).
- Validation that these keys/shapes are present on write lives in `MetadataValidator` (one place, type-dispatched switch) — so the "model" is enforced at the service edge even though the storage is JSON. Unknown `type` or unknown `fee_kind` → 400.

The snapshot path copies this JSON verbatim into `invoice_lines.metadata_snapshot`, so a post-invoice edit to the transaction's `metadata` can't retroactively mutate the invoice.

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

## PATCH semantics: full replace, not partial

`PATCH /transactions/{id}` and `PATCH /invoices/{id}` take the **same body shape as create** and replace the stored resource wholesale (header fields + tax/fee lines or transaction set). Sending `{ "total": 250 }` on its own returns 400 — the request must carry a complete representation.

This is a deliberate choice over RFC 5789 merge-patch semantics:

- The brief defines PATCH as "update stored fields… as you support" and leaves the shape open, so a full-replace is in-scope.
- Tax/fee lines and the invoice line set are **collections**, not scalar fields. Partial-updating a collection would mean inventing a JSON-Patch-style operation vocabulary (add/remove/replace at specific indexes) or guessing when "`tax_lines: []`" means "clear" vs "no change". Full-replace sidesteps that ambiguity: whatever the body contains is what the resource is after the write.
- Snapshot immutability (invoiced-check) is identical either way — the guard runs first and 409s regardless of body shape, so there's no interaction between PATCH-shape and the freeze guarantee.

The trade-off: callers have to GET-then-PATCH if they want to touch one field. That's documented in `curls.md` with a worked example. The sharper HTTP-ism would be to expose this as `PUT`, keep `PATCH` for merge-patch, and add a separate scoped endpoint for the invoiced guard — not worth the surface area at this size.

## Pool size and SQLite concurrency

Hikari runs with `maximum-pool-size: 10` against a SQLite file opened in **WAL** mode (`journal_mode=WAL`), with `busy_timeout=5000ms` and `synchronous=NORMAL` set via JDBC URL params so they apply to every connection in the pool.

The tradeoff behind these numbers:

- **Why a pool at all.** With `maximum-pool-size: 1`, every request serializes at Hikari before it reaches SQLite. The 16-thread concurrent-create test then never actually races at the DB — it queues on the pool lock, and the DB-level unique-index guard is theoretical. Raising the pool makes that test meaningful: 16 clients really do contend inside SQLite, the unique index really does reject the duplicates, and the catch-and-return path really does run under contention.
- **Why WAL.** In the default rollback-journal mode, SQLite blocks concurrent readers against a writer and rejects concurrent writers immediately with `SQLITE_BUSY`. A pool > 1 under those semantics means sporadic BUSY errors on any overlap. WAL lets readers and writers coexist, and only serializes writers against each other — exactly the shape of this workload.
- **Why `busy_timeout=5000`.** When two writes do collide (both coming through the pool, both trying to append to the WAL), SQLite still only lets one writer commit at a time. `busy_timeout` tells the driver to retry transparently for up to 5 s instead of surfacing a `SQLITE_BUSY` the client has to handle. The invoice/transaction writes are small and committed quickly, so 5 s is a generous ceiling. If the timeout is ever exhausted the driver raises `SQLITE_BUSY`, which Spring maps to `TransientDataAccessException` and the global error handler returns `500` — no partial write (each handler runs in a single `@Transactional`), and the caller is free to retry. Under duplicate-POST contention the collision usually surfaces as the unique-index violation rather than BUSY, because the losing writer gets as far as the index insert before being rejected.
- **Why 10, not higher.** SQLite's writer bottleneck is unchanged — more pool capacity past the point where readers already parallelize buys nothing but memory. 10 is enough headroom for the test suite + a handful of concurrent clients without pretending we have real multi-writer concurrency.

If this moved to Postgres the pool sizing and WAL rationale go away: Postgres is natively multi-writer, and the right pool size is a function of CPU and I/O concurrency, not of journal mode.

## High-volume behavior

List endpoints are hard-capped: `limit` default 50, `limit > 100` → 400. There's no cursor pagination because the brief doesn't require it; for a dataset where new rows keep landing, swapping offset for a keyset cursor on `created_at, id` is the next move.

The thing that fails first under load is **not** the invoice path — the `invoice_lines.transaction_id` index keeps the invoiced-check O(1), and the FK + unique indexes do all the heavy lifting on writes. The thing that fails first is an unbounded list query (hence the hard cap) and PATCH contention on a hot `external_id` where many clients retry the same duplicate — the retries all do the duplicate-select path, which is cheap, but there's no backpressure on the client. In practice, the SQLite file lock is the first real bottleneck; moving to Postgres (and taking the `FOR UPDATE` fix above with it) is what unblocks real concurrency.
