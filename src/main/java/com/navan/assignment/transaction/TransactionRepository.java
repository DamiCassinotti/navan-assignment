package com.navan.assignment.transaction;

import static com.navan.assignment.jooq.tables.FeeLines.FEE_LINES;
import static com.navan.assignment.jooq.tables.TaxLines.TAX_LINES;
import static com.navan.assignment.jooq.tables.Transactions.TRANSACTIONS;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navan.assignment.transaction.model.FeeLine;
import com.navan.assignment.transaction.model.TaxLine;
import com.navan.assignment.transaction.model.Transaction;
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
public class TransactionRepository {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public TransactionRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public void insertHeader(Transaction transaction) {
        dsl.insertInto(TRANSACTIONS)
                .set(TRANSACTIONS.ID, transaction.id())
                .set(TRANSACTIONS.EXTERNAL_ID, transaction.externalId())
                .set(TRANSACTIONS.TYPE, transaction.type().wire())
                .set(TRANSACTIONS.CURRENCY, transaction.currency())
                .set(TRANSACTIONS.OCCURRED_AT, transaction.occurredAt().toString())
                .set(TRANSACTIONS.TOTAL, transaction.total().toPlainString())
                .set(TRANSACTIONS.METADATA, writeJson(transaction.metadata()))
                .set(TRANSACTIONS.CREATED_AT, transaction.createdAt().toString())
                .set(TRANSACTIONS.UPDATED_AT, transaction.updatedAt().toString())
                .execute();
    }

    public void insertLines(Transaction transaction) {
        for (TaxLine taxLine : transaction.taxLines()) {
            dsl.insertInto(TAX_LINES)
                    .set(TAX_LINES.ID, UUID.randomUUID())
                    .set(TAX_LINES.TRANSACTION_ID, transaction.id())
                    .set(TAX_LINES.NAME, taxLine.name())
                    .set(TAX_LINES.RATE, taxLine.rate() == null ? null : taxLine.rate().toPlainString())
                    .set(TAX_LINES.AMOUNT, taxLine.amount().toPlainString())
                    .execute();
        }
        for (FeeLine feeLine : transaction.feeLines()) {
            dsl.insertInto(FEE_LINES)
                    .set(FEE_LINES.ID, UUID.randomUUID())
                    .set(FEE_LINES.TRANSACTION_ID, transaction.id())
                    .set(FEE_LINES.NAME, feeLine.name())
                    .set(FEE_LINES.AMOUNT, feeLine.amount().toPlainString())
                    .execute();
        }
    }

    public Optional<Transaction> findById(UUID id) {
        Record row = dsl.selectFrom(TRANSACTIONS).where(TRANSACTIONS.ID.eq(id)).fetchOne();
        return row == null ? Optional.empty() : Optional.of(hydrate(row));
    }

    public Optional<Transaction> findByExternalId(String externalId) {
        Record row = dsl.selectFrom(TRANSACTIONS).where(TRANSACTIONS.EXTERNAL_ID.eq(externalId)).fetchOne();
        return row == null ? Optional.empty() : Optional.of(hydrate(row));
    }

    private Transaction hydrate(Record row) {
        UUID id = row.get(TRANSACTIONS.ID);
        List<TaxLine> taxes = new ArrayList<>();
        dsl.selectFrom(TAX_LINES).where(TAX_LINES.TRANSACTION_ID.eq(id)).fetch().forEach(taxRow ->
                taxes.add(new TaxLine(
                        taxRow.get(TAX_LINES.NAME),
                        taxRow.get(TAX_LINES.RATE) == null ? null : new BigDecimal(taxRow.get(TAX_LINES.RATE)),
                        new BigDecimal(taxRow.get(TAX_LINES.AMOUNT))
                )));
        List<FeeLine> fees = new ArrayList<>();
        dsl.selectFrom(FEE_LINES).where(FEE_LINES.TRANSACTION_ID.eq(id)).fetch().forEach(feeRow ->
                fees.add(new FeeLine(
                        feeRow.get(FEE_LINES.NAME),
                        new BigDecimal(feeRow.get(FEE_LINES.AMOUNT))
                )));
        return new Transaction(
                id,
                row.get(TRANSACTIONS.EXTERNAL_ID),
                TransactionType.fromWire(row.get(TRANSACTIONS.TYPE)),
                row.get(TRANSACTIONS.CURRENCY),
                Instant.parse(row.get(TRANSACTIONS.OCCURRED_AT)),
                new BigDecimal(row.get(TRANSACTIONS.TOTAL)),
                readJson(row.get(TRANSACTIONS.METADATA)),
                taxes,
                fees,
                Instant.parse(row.get(TRANSACTIONS.CREATED_AT)),
                Instant.parse(row.get(TRANSACTIONS.UPDATED_AT))
        );
    }

    private String writeJson(JsonNode node) {
        try { return objectMapper.writeValueAsString(node); }
        catch (JsonProcessingException cause) { throw new IllegalStateException(cause); }
    }

    private JsonNode readJson(String raw) {
        try { return objectMapper.readTree(raw); }
        catch (JsonProcessingException cause) { throw new IllegalStateException(cause); }
    }
}
