package com.navan.assignment.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.assignment.invoice.model.Invoice;
import com.navan.assignment.invoice.model.InvoiceFeeLine;
import com.navan.assignment.invoice.model.InvoiceLine;
import com.navan.assignment.invoice.model.InvoiceTaxLine;
import com.navan.assignment.invoice.model.RollupEntry;
import com.navan.assignment.transaction.model.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        String currency,
        BigDecimal grandTotal,
        List<RollupEntry> taxRollup,
        List<RollupEntry> feeRollup,
        List<Line> lines,
        Instant createdAt
) {
    public record Line(
            UUID id,
            UUID transactionId,
            TransactionType type,
            String currency,
            Instant occurredAt,
            BigDecimal total,
            JsonNode metadataSnapshot,
            List<InvoiceTaxLine> taxLines,
            List<InvoiceFeeLine> feeLines
    ) {
        static Line from(InvoiceLine source) {
            return new Line(
                    source.id(),
                    source.transactionId(),
                    source.type(),
                    source.currency(),
                    source.occurredAt(),
                    source.total(),
                    source.metadataSnapshot(),
                    source.taxLines(),
                    source.feeLines()
            );
        }
    }

    public static InvoiceResponse from(Invoice source) {
        return new InvoiceResponse(
                source.id(),
                source.currency(),
                source.grandTotal(),
                source.taxRollup(),
                source.feeRollup(),
                source.lines().stream().map(Line::from).toList(),
                source.createdAt()
        );
    }
}
