package com.navan.assignment.invoice.model;

import java.math.BigDecimal;

public record InvoiceTaxLine(String name, BigDecimal rate, BigDecimal amount) {}
