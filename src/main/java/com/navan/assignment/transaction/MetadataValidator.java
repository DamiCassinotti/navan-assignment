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
            case FLIGHT -> validateFlight(metadata);
            case HOTEL -> validateHotel(metadata);
            case RAIL -> validateRail(metadata);
            case NAVAN_FEE -> validateNavanFee(metadata);
        }
    }

    private static void validateFlight(JsonNode m) {
        requireText(m, "flight.metadata.origin", "origin");
        requireText(m, "flight.metadata.destination", "destination");
        requireNumber(m, "flight.metadata.fare", "fare");
        JsonNode coupons = m.get("coupons");
        if (coupons == null || !coupons.isArray()) {
            throw new BadRequestException("flight.metadata.coupons must be an array");
        }
        for (int i = 0; i < coupons.size(); i++) {
            JsonNode coupon = coupons.get(i);
            String base = "flight.metadata.coupons[" + i + "]";
            if (coupon == null || !coupon.isObject()) {
                throw new BadRequestException(base + " must be an object");
            }
            requireText(coupon, base + ".flight_number", "flight_number");
            requireText(coupon, base + ".from", "from");
            requireText(coupon, base + ".to", "to");
            requireText(coupon, base + ".departure", "departure");
            requireText(coupon, base + ".arrival", "arrival");
        }
    }

    private static void validateHotel(JsonNode m) {
        requireText(m, "hotel.metadata.name", "name");
        JsonNode address = m.get("address");
        if (address == null || !address.isObject()) {
            throw new BadRequestException("hotel.metadata.address must be an object");
        }
        requireText(address, "hotel.metadata.address.street", "street");
        requireText(address, "hotel.metadata.address.city", "city");
        requireText(address, "hotel.metadata.address.country", "country");
        requireText(m, "hotel.metadata.check_in", "check_in");
        requireText(m, "hotel.metadata.check_out", "check_out");
        requireNumber(m, "hotel.metadata.nights", "nights");
        requireNumber(m, "hotel.metadata.room_rate_per_night", "room_rate_per_night");
    }

    private static void validateRail(JsonNode m) {
        JsonNode legs = m.get("legs");
        if (legs == null || !legs.isArray() || legs.isEmpty()) {
            throw new BadRequestException("rail.metadata.legs must be a non-empty array");
        }
        for (int i = 0; i < legs.size(); i++) {
            JsonNode leg = legs.get(i);
            String base = "rail.metadata.legs[" + i + "]";
            if (leg == null || !leg.isObject()) {
                throw new BadRequestException(base + " must be an object");
            }
            requireText(leg, base + ".origin", "origin");
            requireText(leg, base + ".destination", "destination");
            requireText(leg, base + ".departure", "departure");
            requireText(leg, base + ".arrival", "arrival");
        }
    }

    private static void validateNavanFee(JsonNode m) {
        String kind = textOrNull(m, "fee_kind");
        if (kind == null || !NAVAN_FEE_KINDS.contains(kind)) {
            throw new BadRequestException(
                    "navan_fee.metadata.fee_kind must be one of " + NAVAN_FEE_KINDS);
        }
    }

    private static void requireText(JsonNode node, String path, String field) {
        if (textOrNull(node, field) == null) {
            throw new BadRequestException(path + " is required");
        }
    }

    private static void requireNumber(JsonNode node, String path, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            throw new BadRequestException(path + " must be a number");
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) return null;
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
