package com.navan.assignment.transaction;

import com.navan.assignment.transaction.model.Transaction;
import java.util.List;

public record TransactionPage(List<Transaction> items, int limit, int offset, boolean hasMore) {
}
