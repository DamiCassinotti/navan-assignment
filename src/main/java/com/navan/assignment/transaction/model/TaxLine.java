package com.navan.assignment.transaction.model;

import java.math.BigDecimal;

public record TaxLine(String name, BigDecimal rate, BigDecimal amount) {}
