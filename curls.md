# Manual curls

Run against a locally started service (`./gradlew bootRun`, default port `8080`). Grouped by endpoint; each group covers happy path + the rejections worth eyeballing. Snippets using `jq` assume `jq` is installed; swap for raw bodies otherwise.

## `GET /health`

```bash
curl -s localhost:8080/health
# → {"status":"ok"}
```

## `POST /transactions`

```bash
# Flight (fresh) — expect 201
curl -i -X POST localhost:8080/transactions \
  -H 'content-type: application/json' \
  -d '{
    "type":"flight",
    "external_id":"flight-TXL-LHR-2026-03-11",
    "currency":"EUR",
    "occurred_at":"2026-03-11T06:40:00Z",
    "total":220.00,
    "tax_lines":[{"name":"Airport tax","amount":15.00}],
    "fee_lines":[{"name":"YQ","amount":25.00}],
    "metadata":{"origin":"TXL","destination":"LHR","fare":180.0}
  }'

# Duplicate POST — same external_id → 200 OK, same id as the first; body is the ORIGINAL row
curl -i -X POST localhost:8080/transactions \
  -H 'content-type: application/json' \
  -d '{
    "type":"flight",
    "external_id":"flight-TXL-LHR-2026-03-11",
    "currency":"EUR",
    "occurred_at":"2026-03-11T06:40:00Z",
    "total":999.99,
    "tax_lines":[],"fee_lines":[],
    "metadata":{"origin":"TXL","destination":"LHR"}
  }'

# Hotel, rail, both navan_fee variants — straight from the fixtures
curl -i -X POST localhost:8080/transactions -H 'content-type: application/json' \
  -d "$(jq '.hotel' fixtures/task-b/seed-payloads.json)"
curl -i -X POST localhost:8080/transactions -H 'content-type: application/json' \
  -d "$(jq '.rail' fixtures/task-b/seed-payloads.json)"
curl -i -X POST localhost:8080/transactions -H 'content-type: application/json' \
  -d "$(jq '.trip_fee' fixtures/task-b/seed-payloads.json)"
curl -i -X POST localhost:8080/transactions -H 'content-type: application/json' \
  -d "$(jq '.agent_call_fee' fixtures/task-b/seed-payloads.json)"
```

### Rejections — 400

```bash
# Bad navan_fee kind
curl -i -X POST localhost:8080/transactions \
  -H 'content-type: application/json' \
  -d '{
    "type":"navan_fee","external_id":"bad-navan-1","currency":"EUR",
    "occurred_at":"2026-03-11T06:40:00Z","total":10,
    "metadata":{"fee_kind":"not_a_real_kind"},
    "tax_lines":[],"fee_lines":[]
  }'

# Missing external_id
curl -i -X POST localhost:8080/transactions \
  -H 'content-type: application/json' \
  -d '{"type":"flight","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":10,"metadata":{"origin":"A","destination":"B"},"tax_lines":[],"fee_lines":[]}'

# Rail with empty legs
curl -i -X POST localhost:8080/transactions \
  -H 'content-type: application/json' \
  -d '{"type":"rail","external_id":"rail-empty","currency":"EUR","occurred_at":"2026-03-13T09:00:00Z","total":10,"metadata":{"legs":[]},"tax_lines":[],"fee_lines":[]}'
```

## `GET /transactions/{id}`

```bash
# Round-trip — expect 200 with full body including metadata + tax/fee lines
ID=$(curl -s -X POST localhost:8080/transactions -H 'content-type: application/json' \
       -d "$(jq '.hotel' fixtures/task-b/seed-payloads.json)" | jq -r .id)
curl -s localhost:8080/transactions/$ID | jq

# Unknown id — expect 404
curl -i localhost:8080/transactions/00000000-0000-0000-0000-000000000000
```

## `PATCH /transactions/{id}`

Atomic replace of the header fields plus tax/fee lines. `external_id` is immutable.

```bash
# Happy path — 200 with the updated body
ID=$(curl -s -X POST localhost:8080/transactions -H 'content-type: application/json' \
       -d "$(jq '.flight' fixtures/task-b/seed-payloads.json)" | jq -r .id)

curl -i -X PATCH localhost:8080/transactions/$ID \
  -H 'content-type: application/json' \
  -d '{
    "type":"flight","currency":"USD",
    "occurred_at":"2026-04-01T10:00:00Z","total":333.33,
    "metadata":{"origin":"TXL","destination":"CDG"},
    "tax_lines":[{"name":"VAT","rate":0.1,"amount":30.30}],
    "fee_lines":[{"name":"booking","amount":5.00}]
  }'

# Unknown id — 404
curl -i -X PATCH localhost:8080/transactions/00000000-0000-0000-0000-000000000000 \
  -H 'content-type: application/json' \
  -d '{"type":"flight","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":10,"metadata":{"origin":"A","destination":"B"},"tax_lines":[],"fee_lines":[]}'

# Bad metadata — 400, row unchanged
curl -i -X PATCH localhost:8080/transactions/$ID \
  -H 'content-type: application/json' \
  -d '{"type":"navan_fee","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":10,"metadata":{"fee_kind":"nope"},"tax_lines":[],"fee_lines":[]}'
```

## `DELETE /transactions/{id}`

Cascades to tax/fee lines.

```bash
# Happy path — 204
ID=$(curl -s -X POST localhost:8080/transactions -H 'content-type: application/json' \
       -d "$(jq '.hotel' fixtures/task-b/seed-payloads.json)" | jq -r .id)
curl -i -X DELETE localhost:8080/transactions/$ID

# Unknown id — 404
curl -i -X DELETE localhost:8080/transactions/00000000-0000-0000-0000-000000000000
```

## `GET /transactions`

```bash
# Default page — limit=50, offset=0; response is { items, limit, offset, has_more }
curl -s localhost:8080/transactions | jq

# Explicit paging — walk with offset while has_more is true
curl -s 'localhost:8080/transactions?limit=10&offset=20' | jq

# limit > 100 — expect 400
curl -i 'localhost:8080/transactions?limit=1000'
```

## `POST /invoices`

Accepts either `transaction_id` (single) or `transaction_ids` (array). Repeated ids collapse to one snapshot line. Response is the full invoice: snapshot `lines`, cached `tax_rollup` / `fee_rollup`, and `grand_total`.

```bash
# Full trip invoice — 5 seeds → grand_total 685.70
FLIGHT=$(curl -s -X POST localhost:8080/transactions -H 'content-type: application/json' \
          -d "$(jq '.flight' fixtures/task-b/seed-payloads.json)" | jq -r .id)
HOTEL=$(curl -s -X POST localhost:8080/transactions -H 'content-type: application/json' \
         -d "$(jq '.hotel' fixtures/task-b/seed-payloads.json)" | jq -r .id)
RAIL=$(curl -s -X POST localhost:8080/transactions -H 'content-type: application/json' \
        -d "$(jq '.rail' fixtures/task-b/seed-payloads.json)" | jq -r .id)
TRIP=$(curl -s -X POST localhost:8080/transactions -H 'content-type: application/json' \
        -d "$(jq '.trip_fee' fixtures/task-b/seed-payloads.json)" | jq -r .id)
CALL=$(curl -s -X POST localhost:8080/transactions -H 'content-type: application/json' \
        -d "$(jq '.agent_call_fee' fixtures/task-b/seed-payloads.json)" | jq -r .id)

INVOICE_ID=$(curl -s -X POST localhost:8080/invoices \
  -H 'content-type: application/json' \
  -d "{\"transaction_ids\":[\"$FLIGHT\",\"$HOTEL\",\"$RAIL\",\"$TRIP\",\"$CALL\"]}" | jq -r .id)

# Single id — alternative form
curl -i -X POST localhost:8080/invoices \
  -H 'content-type: application/json' \
  -d "{\"transaction_id\":\"$FLIGHT\"}"
```

### Rejections

```bash
# Missing transaction — 404
curl -i -X POST localhost:8080/invoices \
  -H 'content-type: application/json' \
  -d '{"transaction_id":"00000000-0000-0000-0000-000000000000"}'

# Mixed currencies — 409. Create a USD hotel transaction first, then invoice EUR + USD together.
USD_HOTEL_ID=$(curl -s -X POST localhost:8080/transactions -H 'content-type: application/json' \
  -d "$(jq '.hotel + {currency:"USD", external_id:"hotel-USD-mix"}' fixtures/task-b/seed-payloads.json)" \
  | jq -r .id)

curl -i -X POST localhost:8080/invoices \
  -H 'content-type: application/json' \
  -d "{\"transaction_ids\":[\"$FLIGHT\",\"$USD_HOTEL_ID\"]}"
```

## `GET /invoices/{id}`

`INVOICE_ID` is set by the trip-invoice snippet above.

```bash
# Read back the cached rollups + snapshot lines
curl -s localhost:8080/invoices/$INVOICE_ID | jq

# Unknown id — 404
curl -i localhost:8080/invoices/00000000-0000-0000-0000-000000000000
```

## `GET /invoices`

```bash
# First page (defaults: limit=50, offset=0)
curl -s localhost:8080/invoices | jq

# Paged
curl -s "localhost:8080/invoices?limit=2&offset=0" | jq
curl -s "localhost:8080/invoices?limit=2&offset=2" | jq

# limit > 100 — 400
curl -i "localhost:8080/invoices?limit=101"
```

## `PATCH /invoices/{id}`

Same body shape as `POST /invoices`. Re-snapshots atomically: old snapshot rows are dropped, new ones inserted, rollups recomputed. `created_at` is preserved.

```bash
# Swap the tx set behind an existing invoice
curl -i -X PATCH localhost:8080/invoices/$INVOICE_ID \
  -H 'content-type: application/json' \
  -d "{\"transaction_ids\":[\"$HOTEL\",\"$RAIL\"]}"

# Unknown invoice — 404
curl -i -X PATCH localhost:8080/invoices/00000000-0000-0000-0000-000000000000 \
  -H 'content-type: application/json' \
  -d "{\"transaction_id\":\"$FLIGHT\"}"

# Mixed currencies — 409 (invoice unchanged)
curl -i -X PATCH localhost:8080/invoices/$INVOICE_ID \
  -H 'content-type: application/json' \
  -d "{\"transaction_ids\":[\"$FLIGHT\",\"$USD_HOTEL_ID\"]}"
```

## `DELETE /invoices/{id}`

Removes the invoice row and its snapshot rows (`invoice_lines`, `invoice_tax_lines`, `invoice_fee_lines`). Underlying transactions are left untouched.

```bash
curl -i -X DELETE localhost:8080/invoices/$INVOICE_ID

# Unknown id — 404
curl -i -X DELETE localhost:8080/invoices/00000000-0000-0000-0000-000000000000
```

## Invoiced-transaction guard

Any transaction that currently has a row in `invoice_lines` can't be mutated or removed. `PATCH /transactions/{id}` and `DELETE /transactions/{id}` return **409** without writing.

```bash
# After POST /invoices with $FLIGHT, both of these are rejected:
curl -i -X PATCH localhost:8080/transactions/$FLIGHT \
  -H 'content-type: application/json' \
  -d '{"type":"flight","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":999.99,"metadata":{"origin":"TXL","destination":"LHR"},"tax_lines":[],"fee_lines":[]}'

curl -i -X DELETE localhost:8080/transactions/$FLIGHT
```
