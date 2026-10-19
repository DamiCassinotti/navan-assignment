package com.navan.assignment.web.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record CreateInvoiceRequest(UUID transactionId, List<UUID> transactionIds) {
    public List<UUID> ids() {
        List<UUID> result = new ArrayList<>();
        if (transactionIds != null) result.addAll(transactionIds);
        if (transactionId != null) result.add(transactionId);
        return result;
    }
}
