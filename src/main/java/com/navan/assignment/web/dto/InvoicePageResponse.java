package com.navan.assignment.web.dto;

import java.util.List;

public record InvoicePageResponse(
        List<InvoiceResponse> items,
        int limit,
        int offset,
        boolean hasMore
) {
}
