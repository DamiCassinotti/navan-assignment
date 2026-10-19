package com.navan.assignment.invoice.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Invoice(
        UUID id,
        String currency,
        BigDecimal grandTotal,
        List<RollupEntry> taxRollup,
        List<RollupEntry> feeRollup,
        List<InvoiceLine> lines,
        Instant createdAt
) {}
