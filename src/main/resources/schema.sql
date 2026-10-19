CREATE TABLE IF NOT EXISTS transactions (
    id           BLOB NOT NULL PRIMARY KEY,
    external_id  TEXT NOT NULL,
    type         TEXT NOT NULL,
    currency     TEXT NOT NULL,
    occurred_at  TEXT NOT NULL,
    total        TEXT NOT NULL,
    metadata     TEXT NOT NULL,
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_transactions_external_id ON transactions(external_id);

CREATE TABLE IF NOT EXISTS tax_lines (
    id             BLOB NOT NULL PRIMARY KEY,
    transaction_id BLOB NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    name           TEXT NOT NULL,
    rate           TEXT,
    amount         TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_tax_lines_transaction_id ON tax_lines(transaction_id);

CREATE TABLE IF NOT EXISTS fee_lines (
    id             BLOB NOT NULL PRIMARY KEY,
    transaction_id BLOB NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    name           TEXT NOT NULL,
    amount         TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_fee_lines_transaction_id ON fee_lines(transaction_id);

CREATE TABLE IF NOT EXISTS invoices (
    id          BLOB NOT NULL PRIMARY KEY,
    currency    TEXT NOT NULL,
    grand_total TEXT NOT NULL,
    tax_rollup  TEXT NOT NULL,
    fee_rollup  TEXT NOT NULL,
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS invoice_lines (
    id                BLOB NOT NULL PRIMARY KEY,
    invoice_id        BLOB NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    transaction_id    BLOB NOT NULL REFERENCES transactions(id),
    type              TEXT NOT NULL,
    currency          TEXT NOT NULL,
    occurred_at       TEXT NOT NULL,
    total             TEXT NOT NULL,
    metadata_snapshot TEXT NOT NULL,
    UNIQUE(invoice_id, transaction_id)
);

CREATE INDEX IF NOT EXISTS ix_invoice_lines_invoice_id ON invoice_lines(invoice_id);
CREATE INDEX IF NOT EXISTS ix_invoice_lines_transaction_id ON invoice_lines(transaction_id);

CREATE TABLE IF NOT EXISTS invoice_tax_lines (
    id              BLOB NOT NULL PRIMARY KEY,
    invoice_line_id BLOB NOT NULL REFERENCES invoice_lines(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    rate            TEXT,
    amount          TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_invoice_tax_lines_line_id ON invoice_tax_lines(invoice_line_id);

CREATE TABLE IF NOT EXISTS invoice_fee_lines (
    id              BLOB NOT NULL PRIMARY KEY,
    invoice_line_id BLOB NOT NULL REFERENCES invoice_lines(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    amount          TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_invoice_fee_lines_line_id ON invoice_fee_lines(invoice_line_id);
