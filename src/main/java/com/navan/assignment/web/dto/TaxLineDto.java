package com.navan.assignment.web.dto;

import java.math.BigDecimal;

public record TaxLineDto(String name, BigDecimal rate, BigDecimal amount) {}
