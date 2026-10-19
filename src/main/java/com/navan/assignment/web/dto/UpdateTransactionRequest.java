package com.navan.assignment.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.assignment.transaction.model.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record UpdateTransactionRequest(
        TransactionType type,
        String currency,
        Instant occurredAt,
        BigDecimal total,
        JsonNode metadata,
        List<TaxLineDto> taxLines,
        List<FeeLineDto> feeLines
) {}
