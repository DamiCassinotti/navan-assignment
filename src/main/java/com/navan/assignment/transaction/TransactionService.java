package com.navan.assignment.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.assignment.common.errors.BadRequestException;
import com.navan.assignment.common.errors.NotFoundException;
import com.navan.assignment.transaction.model.FeeLine;
import com.navan.assignment.transaction.model.TaxLine;
import com.navan.assignment.transaction.model.Transaction;
import com.navan.assignment.transaction.model.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jooq.exception.IntegrityConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final MetadataValidator metadataValidator;

    public TransactionService(TransactionRepository transactionRepository, MetadataValidator metadataValidator) {
        this.transactionRepository = transactionRepository;
        this.metadataValidator = metadataValidator;
    }

    @Transactional
    public CreateTransactionResult create(
            String externalId,
            TransactionType type,
            String currency,
            Instant occurredAt,
            BigDecimal total,
            JsonNode metadata,
            List<TaxLine> taxLines,
            List<FeeLine> feeLines) {

        metadataValidator.validate(type, metadata);

        Instant now = Instant.now();
        Transaction transaction = new Transaction(
                UUID.randomUUID(),
                externalId,
                type,
                currency,
                occurredAt,
                total,
                metadata,
                taxLines == null ? List.of() : taxLines,
                feeLines == null ? List.of() : feeLines,
                now,
                now
        );

        try {
            transactionRepository.insertHeader(transaction);
        } catch (DataIntegrityViolationException | IntegrityConstraintViolationException dup) {
            Transaction existing = transactionRepository.findByExternalId(externalId)
                    .orElseThrow(() -> dup);
            return new CreateTransactionResult(existing, false);
        }
        transactionRepository.insertLines(transaction);
        return new CreateTransactionResult(transaction, true);
    }

    public Transaction findById(UUID id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("transaction not found: " + id));
    }

    public TransactionPage findPage(int limit, int offset) {
        if (limit < 1) throw new BadRequestException("limit must be >= 1");
        if (limit > 100) throw new BadRequestException("limit must be <= 100");
        if (offset < 0) throw new BadRequestException("offset must be >= 0");
        List<Transaction> rows = transactionRepository.findAll(limit + 1, offset);
        boolean hasMore = rows.size() > limit;
        List<Transaction> items = hasMore ? rows.subList(0, limit) : rows;
        return new TransactionPage(items, limit, offset, hasMore);
    }
}
