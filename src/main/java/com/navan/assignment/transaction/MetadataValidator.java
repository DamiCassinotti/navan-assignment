package com.navan.assignment.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.assignment.common.errors.BadRequestException;
import com.navan.assignment.transaction.model.TransactionType;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MetadataValidator {

    private static final Set<String> NAVAN_FEE_KINDS = Set.of("trip_fee", "agent_call_fee");

    public void validate(TransactionType type, JsonNode metadata) {
        if (metadata == null || metadata.isNull() || !metadata.isObject()) {
            throw new BadRequestException("metadata must be a JSON object");
        }
        switch (type) {
            case FLIGHT -> {
                requireText(metadata, "origin");
                requireText(metadata, "destination");
            }
            case HOTEL -> {
                requireText(metadata, "name");
                requireText(metadata, "check_in");
                requireText(metadata, "check_out");
            }
            case RAIL -> {
                JsonNode legs = metadata.get("legs");
                if (legs == null || !legs.isArray() || legs.isEmpty()) {
                    throw new BadRequestException("rail.metadata.legs must be a non-empty array");
                }
            }
            case NAVAN_FEE -> {
                String kind = textOrNull(metadata, "fee_kind");
                if (kind == null || !NAVAN_FEE_KINDS.contains(kind)) {
                    throw new BadRequestException(
                            "navan_fee.metadata.fee_kind must be one of " + NAVAN_FEE_KINDS);
                }
            }
        }
    }

    private static void requireText(JsonNode node, String field) {
        if (textOrNull(node, field) == null) {
            throw new BadRequestException("metadata." + field + " is required");
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) return null;
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
