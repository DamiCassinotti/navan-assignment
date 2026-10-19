package com.navan.assignment.invoice.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RollupEntry(String name, BigDecimal rate, BigDecimal amount) {}
