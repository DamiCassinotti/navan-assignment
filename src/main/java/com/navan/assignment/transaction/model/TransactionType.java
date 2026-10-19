package com.navan.assignment.transaction.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum TransactionType {
    FLIGHT, HOTEL, RAIL, NAVAN_FEE;

    @JsonValue
    public String wire() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static TransactionType fromWire(String s) {
        if (s == null) return null;
        return Arrays.stream(values())
                .filter(t -> t.wire().equalsIgnoreCase(s))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown transaction type: " + s));
    }
}
