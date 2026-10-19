package com.navan.assignment.invoice;

import com.navan.assignment.invoice.model.Invoice;
import java.util.List;

public record InvoicePage(List<Invoice> items, int limit, int offset, boolean hasMore) {
}
