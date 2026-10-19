package com.navan.assignment.invoice;

import static com.navan.assignment.jooq.tables.InvoiceFeeLines.INVOICE_FEE_LINES;
import static com.navan.assignment.jooq.tables.InvoiceLines.INVOICE_LINES;
import static com.navan.assignment.jooq.tables.InvoiceTaxLines.INVOICE_TAX_LINES;
import static com.navan.assignment.jooq.tables.Invoices.INVOICES;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navan.assignment.invoice.model.Invoice;
import com.navan.assignment.invoice.model.InvoiceFeeLine;
import com.navan.assignment.invoice.model.InvoiceLine;
import com.navan.assignment.invoice.model.InvoiceTaxLine;
import com.navan.assignment.invoice.model.RollupEntry;
import com.navan.assignment.transaction.model.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class InvoiceRepository {

    private static final TypeReference<List<RollupEntry>> ROLLUP_LIST = new TypeReference<>() {};

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public InvoiceRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public void insert(Invoice invoice) {
        dsl.insertInto(INVOICES)
                .set(INVOICES.ID, invoice.id())
                .set(INVOICES.CURRENCY, invoice.currency())
                .set(INVOICES.GRAND_TOTAL, invoice.grandTotal().toPlainString())
                .set(INVOICES.TAX_ROLLUP, writeJson(invoice.taxRollup()))
                .set(INVOICES.FEE_ROLLUP, writeJson(invoice.feeRollup()))
                .set(INVOICES.CREATED_AT, invoice.createdAt().toString())
                .execute();
        insertLines(invoice);
    }

    public List<Invoice> findAll(int limit, int offset) {
        Map<UUID, Invoice> headers = new LinkedHashMap<>();
        Map<UUID, List<InvoiceLine>> linesByInvoice = new HashMap<>();
        dsl.selectFrom(INVOICES)
                .orderBy(INVOICES.CREATED_AT.asc(), INVOICES.ID.asc())
                .limit(limit)
                .offset(offset)
                .fetch()
                .forEach(row -> {
                    UUID id = row.get(INVOICES.ID);
                    List<InvoiceLine> lines = new ArrayList<>();
                    linesByInvoice.put(id, lines);
                    headers.put(id, new Invoice(
                            id,
                            row.get(INVOICES.CURRENCY),
                            new BigDecimal(row.get(INVOICES.GRAND_TOTAL)),
                            readRollup(row.get(INVOICES.TAX_ROLLUP)),
                            readRollup(row.get(INVOICES.FEE_ROLLUP)),
                            lines,
                            Instant.parse(row.get(INVOICES.CREATED_AT))
                    ));
                });
        if (headers.isEmpty()) return List.of();

        Map<UUID, UUID> lineToInvoice = new HashMap<>();
        Map<UUID, InvoiceLineShell> lineShells = new LinkedHashMap<>();
        dsl.selectFrom(INVOICE_LINES)
                .where(INVOICE_LINES.INVOICE_ID.in(headers.keySet()))
                .fetch()
                .forEach(lineRow -> {
                    UUID lineId = lineRow.get(INVOICE_LINES.ID);
                    lineToInvoice.put(lineId, lineRow.get(INVOICE_LINES.INVOICE_ID));
                    lineShells.put(lineId, new InvoiceLineShell(
                            lineId,
                            lineRow.get(INVOICE_LINES.TRANSACTION_ID),
                            TransactionType.fromWire(lineRow.get(INVOICE_LINES.TYPE)),
                            lineRow.get(INVOICE_LINES.CURRENCY),
                            Instant.parse(lineRow.get(INVOICE_LINES.OCCURRED_AT)),
                            new BigDecimal(lineRow.get(INVOICE_LINES.TOTAL)),
                            readJson(lineRow.get(INVOICE_LINES.METADATA_SNAPSHOT)),
                            new ArrayList<>(),
                            new ArrayList<>()
                    ));
                });

        if (!lineShells.isEmpty()) {
            dsl.selectFrom(INVOICE_TAX_LINES)
                    .where(INVOICE_TAX_LINES.INVOICE_LINE_ID.in(lineShells.keySet()))
                    .fetch()
                    .forEach(taxRow -> {
                        InvoiceLineShell shell = lineShells.get(taxRow.get(INVOICE_TAX_LINES.INVOICE_LINE_ID));
                        if (shell != null) shell.taxLines.add(new InvoiceTaxLine(
                                taxRow.get(INVOICE_TAX_LINES.NAME),
                                taxRow.get(INVOICE_TAX_LINES.RATE) == null ? null : new BigDecimal(taxRow.get(INVOICE_TAX_LINES.RATE)),
                                new BigDecimal(taxRow.get(INVOICE_TAX_LINES.AMOUNT))
                        ));
                    });
            dsl.selectFrom(INVOICE_FEE_LINES)
                    .where(INVOICE_FEE_LINES.INVOICE_LINE_ID.in(lineShells.keySet()))
                    .fetch()
                    .forEach(feeRow -> {
                        InvoiceLineShell shell = lineShells.get(feeRow.get(INVOICE_FEE_LINES.INVOICE_LINE_ID));
                        if (shell != null) shell.feeLines.add(new InvoiceFeeLine(
                                feeRow.get(INVOICE_FEE_LINES.NAME),
                                new BigDecimal(feeRow.get(INVOICE_FEE_LINES.AMOUNT))
                        ));
                    });
        }

        for (InvoiceLineShell shell : lineShells.values()) {
            UUID invoiceId = lineToInvoice.get(shell.id);
            linesByInvoice.get(invoiceId).add(new InvoiceLine(
                    shell.id, shell.transactionId, shell.type, shell.currency, shell.occurredAt,
                    shell.total, shell.metadataSnapshot, shell.taxLines, shell.feeLines
            ));
        }

        return new ArrayList<>(headers.values());
    }

    private static final class InvoiceLineShell {
        final UUID id;
        final UUID transactionId;
        final TransactionType type;
        final String currency;
        final Instant occurredAt;
        final BigDecimal total;
        final JsonNode metadataSnapshot;
        final List<InvoiceTaxLine> taxLines;
        final List<InvoiceFeeLine> feeLines;

        InvoiceLineShell(UUID id, UUID transactionId, TransactionType type, String currency,
                         Instant occurredAt, BigDecimal total, JsonNode metadataSnapshot,
                         List<InvoiceTaxLine> taxLines, List<InvoiceFeeLine> feeLines) {
            this.id = id;
            this.transactionId = transactionId;
            this.type = type;
            this.currency = currency;
            this.occurredAt = occurredAt;
            this.total = total;
            this.metadataSnapshot = metadataSnapshot;
            this.taxLines = taxLines;
            this.feeLines = feeLines;
        }
    }

    public void deleteSnapshotRows(UUID invoiceId) {
        List<UUID> lineIds = dsl.select(INVOICE_LINES.ID)
                .from(INVOICE_LINES)
                .where(INVOICE_LINES.INVOICE_ID.eq(invoiceId))
                .fetch(INVOICE_LINES.ID);
        if (!lineIds.isEmpty()) {
            dsl.deleteFrom(INVOICE_TAX_LINES).where(INVOICE_TAX_LINES.INVOICE_LINE_ID.in(lineIds)).execute();
            dsl.deleteFrom(INVOICE_FEE_LINES).where(INVOICE_FEE_LINES.INVOICE_LINE_ID.in(lineIds)).execute();
        }
        dsl.deleteFrom(INVOICE_LINES).where(INVOICE_LINES.INVOICE_ID.eq(invoiceId)).execute();
    }

    public void updateHeader(Invoice invoice) {
        dsl.update(INVOICES)
                .set(INVOICES.CURRENCY, invoice.currency())
                .set(INVOICES.GRAND_TOTAL, invoice.grandTotal().toPlainString())
                .set(INVOICES.TAX_ROLLUP, writeJson(invoice.taxRollup()))
                .set(INVOICES.FEE_ROLLUP, writeJson(invoice.feeRollup()))
                .where(INVOICES.ID.eq(invoice.id()))
                .execute();
    }

    public void insertLines(Invoice invoice) {
        for (InvoiceLine line : invoice.lines()) {
            dsl.insertInto(INVOICE_LINES)
                    .set(INVOICE_LINES.ID, line.id())
                    .set(INVOICE_LINES.INVOICE_ID, invoice.id())
                    .set(INVOICE_LINES.TRANSACTION_ID, line.transactionId())
                    .set(INVOICE_LINES.TYPE, line.type().wire())
                    .set(INVOICE_LINES.CURRENCY, line.currency())
                    .set(INVOICE_LINES.OCCURRED_AT, line.occurredAt().toString())
                    .set(INVOICE_LINES.TOTAL, line.total().toPlainString())
                    .set(INVOICE_LINES.METADATA_SNAPSHOT, writeJson(line.metadataSnapshot()))
                    .execute();

            for (InvoiceTaxLine tax : line.taxLines()) {
                dsl.insertInto(INVOICE_TAX_LINES)
                        .set(INVOICE_TAX_LINES.ID, UUID.randomUUID())
                        .set(INVOICE_TAX_LINES.INVOICE_LINE_ID, line.id())
                        .set(INVOICE_TAX_LINES.NAME, tax.name())
                        .set(INVOICE_TAX_LINES.RATE, tax.rate() == null ? null : tax.rate().toPlainString())
                        .set(INVOICE_TAX_LINES.AMOUNT, tax.amount().toPlainString())
                        .execute();
            }
            for (InvoiceFeeLine fee : line.feeLines()) {
                dsl.insertInto(INVOICE_FEE_LINES)
                        .set(INVOICE_FEE_LINES.ID, UUID.randomUUID())
                        .set(INVOICE_FEE_LINES.INVOICE_LINE_ID, line.id())
                        .set(INVOICE_FEE_LINES.NAME, fee.name())
                        .set(INVOICE_FEE_LINES.AMOUNT, fee.amount().toPlainString())
                        .execute();
            }
        }
    }

    public int deleteById(UUID invoiceId) {
        deleteSnapshotRows(invoiceId);
        return dsl.deleteFrom(INVOICES).where(INVOICES.ID.eq(invoiceId)).execute();
    }

    public Optional<UUID> findInvoiceIdForTransaction(UUID transactionId) {
        return dsl.select(INVOICE_LINES.INVOICE_ID)
                .from(INVOICE_LINES)
                .where(INVOICE_LINES.TRANSACTION_ID.eq(transactionId))
                .limit(1)
                .fetchOptional(INVOICE_LINES.INVOICE_ID);
    }

    public Optional<Invoice> findById(UUID id) {
        Record row = dsl.selectFrom(INVOICES).where(INVOICES.ID.eq(id)).fetchOne();
        if (row == null) return Optional.empty();

        List<InvoiceLine> lines = new ArrayList<>();
        dsl.selectFrom(INVOICE_LINES)
                .where(INVOICE_LINES.INVOICE_ID.eq(id))
                .fetch()
                .forEach(lineRow -> lines.add(hydrateLine(lineRow)));

        return Optional.of(new Invoice(
                id,
                row.get(INVOICES.CURRENCY),
                new BigDecimal(row.get(INVOICES.GRAND_TOTAL)),
                readRollup(row.get(INVOICES.TAX_ROLLUP)),
                readRollup(row.get(INVOICES.FEE_ROLLUP)),
                lines,
                Instant.parse(row.get(INVOICES.CREATED_AT))
        ));
    }

    private InvoiceLine hydrateLine(Record row) {
        UUID lineId = row.get(INVOICE_LINES.ID);
        List<InvoiceTaxLine> taxes = new ArrayList<>();
        dsl.selectFrom(INVOICE_TAX_LINES)
                .where(INVOICE_TAX_LINES.INVOICE_LINE_ID.eq(lineId))
                .fetch()
                .forEach(taxRow -> taxes.add(new InvoiceTaxLine(
                        taxRow.get(INVOICE_TAX_LINES.NAME),
                        taxRow.get(INVOICE_TAX_LINES.RATE) == null ? null : new BigDecimal(taxRow.get(INVOICE_TAX_LINES.RATE)),
                        new BigDecimal(taxRow.get(INVOICE_TAX_LINES.AMOUNT))
                )));
        List<InvoiceFeeLine> fees = new ArrayList<>();
        dsl.selectFrom(INVOICE_FEE_LINES)
                .where(INVOICE_FEE_LINES.INVOICE_LINE_ID.eq(lineId))
                .fetch()
                .forEach(feeRow -> fees.add(new InvoiceFeeLine(
                        feeRow.get(INVOICE_FEE_LINES.NAME),
                        new BigDecimal(feeRow.get(INVOICE_FEE_LINES.AMOUNT))
                )));
        return new InvoiceLine(
                lineId,
                row.get(INVOICE_LINES.TRANSACTION_ID),
                TransactionType.fromWire(row.get(INVOICE_LINES.TYPE)),
                row.get(INVOICE_LINES.CURRENCY),
                Instant.parse(row.get(INVOICE_LINES.OCCURRED_AT)),
                new BigDecimal(row.get(INVOICE_LINES.TOTAL)),
                readJson(row.get(INVOICE_LINES.METADATA_SNAPSHOT)),
                taxes,
                fees
        );
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException cause) { throw new IllegalStateException(cause); }
    }

    private JsonNode readJson(String raw) {
        try { return objectMapper.readTree(raw); }
        catch (JsonProcessingException cause) { throw new IllegalStateException(cause); }
    }

    private List<RollupEntry> readRollup(String raw) {
        try { return objectMapper.readValue(raw, ROLLUP_LIST); }
        catch (JsonProcessingException cause) { throw new IllegalStateException(cause); }
    }
}
