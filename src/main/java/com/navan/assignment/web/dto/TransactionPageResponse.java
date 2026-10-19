package com.navan.assignment.web.dto;

import java.util.List;

public record TransactionPageResponse(
        List<TransactionResponse> items,
        int limit,
        int offset,
        boolean hasMore
) {
}
