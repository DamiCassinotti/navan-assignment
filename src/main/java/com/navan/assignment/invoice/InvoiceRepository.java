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
import java.util.List;
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

    public List<Invoice> findAll(int limit, int offset) {
        List<Invoice> result = new ArrayList<>();
        dsl.selectFrom(INVOICES)
                .orderBy(INVOICES.CREATED_AT.asc(), INVOICES.ID.asc())
                .limit(limit)
                .offset(offset)
                .fetch()
                .forEach(row -> {
                    UUID id = row.get(INVOICES.ID);
                    List<InvoiceLine> lines = new ArrayList<>();
                    dsl.selectFrom(INVOICE_LINES)
                            .where(INVOICE_LINES.INVOICE_ID.eq(id))
                            .fetch()
                            .forEach(lineRow -> lines.add(hydrateLine(lineRow)));
                    result.add(new Invoice(
                            id,
                            row.get(INVOICES.CURRENCY),
                            new BigDecimal(row.get(INVOICES.GRAND_TOTAL)),
                            readRollup(row.get(INVOICES.TAX_ROLLUP)),
                            readRollup(row.get(INVOICES.FEE_ROLLUP)),
                            lines,
                            Instant.parse(row.get(INVOICES.CREATED_AT))
                    ));
                });
        return result;
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
