package com.navan.assignment.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.assignment.transaction.model.Transaction;
import com.navan.assignment.transaction.model.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String externalId,
        TransactionType type,
        String currency,
        Instant occurredAt,
        BigDecimal total,
        JsonNode metadata,
        List<TaxLineDto> taxLines,
        List<FeeLineDto> feeLines,
        Instant createdAt,
        Instant updatedAt
) {
    public static TransactionResponse from(Transaction source) {
        return new TransactionResponse(
                source.id(),
                source.externalId(),
                source.type(),
                source.currency(),
                source.occurredAt(),
                source.total(),
                source.metadata(),
                source.taxLines().stream()
                        .map(line -> new TaxLineDto(line.name(), line.rate(), line.amount()))
                        .toList(),
                source.feeLines().stream()
                        .map(line -> new FeeLineDto(line.name(), line.amount()))
                        .toList(),
                source.createdAt(),
                source.updatedAt()
        );
    }
}
