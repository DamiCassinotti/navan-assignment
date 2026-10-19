# Manual curls

Run against a locally started service (`./gradlew bootRun`, default port `8080`). Grouped by endpoint; each group covers happy path + the rejections worth eyeballing. Snippets using `jq` assume `jq` is installed; swap for raw bodies otherwise.

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
