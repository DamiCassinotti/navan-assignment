# navan-assignment

Take-home: travel transactions + invoice service. See [`task-b-travel-inventory-invoice-CANDIDATE.md`](task-b-travel-inventory-invoice-CANDIDATE.md) for the brief.

## Stack

- Java 21, Spring Boot 3.3
- SQLite (file at `./data/navan.db`, auto-created on first boot)
- jOOQ 3.19 for queries, with codegen off the project `schema.sql`
- Gradle wrapper

## Run

```bash
./gradlew bootRun
./gradlew test
./gradlew build
```

The DB file lives at `./data/navan.db` and is created on first boot. Delete the file to start fresh — Spring Boot re-runs `schema.sql` on every startup.

## Schema

Schema lives in `src/main/resources/schema.sql` and is applied idempotently (`CREATE TABLE IF NOT EXISTS`) on every boot via Spring Boot's SQL init. No migration tool for now — restart with a fresh DB file when the schema changes.
