package com.navan.assignment.transaction.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Transaction(
        UUID id,
        String externalId,
        TransactionType type,
        String currency,
        Instant occurredAt,
        BigDecimal total,
        JsonNode metadata,
        List<TaxLine> taxLines,
        List<FeeLine> feeLines,
        Instant createdAt,
        Instant updatedAt
) {}
