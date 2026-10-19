package com.navan.assignment.invoice.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.assignment.transaction.model.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InvoiceLine(
        UUID id,
        UUID transactionId,
        TransactionType type,
        String currency,
        Instant occurredAt,
        BigDecimal total,
        JsonNode metadataSnapshot,
        List<InvoiceTaxLine> taxLines,
        List<InvoiceFeeLine> feeLines
) {}
