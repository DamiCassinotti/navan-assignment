package com.navan.assignment.transaction;

import com.navan.assignment.transaction.model.Transaction;

public record CreateTransactionResult(Transaction transaction, boolean created) {}
