package com.navan.assignment.transaction;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navan.assignment.common.errors.BadRequestException;
import com.navan.assignment.transaction.model.TransactionType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MetadataValidatorUnitTests {

    private final MetadataValidator validator = new MetadataValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Nested
    class Flight {
        @Test
        void acceptsOriginAndDestination() throws Exception {
            validator.validate(TransactionType.FLIGHT, json("{\"origin\":\"TXL\",\"destination\":\"LHR\"}"));
        }

        @Test
        void rejectsMissingOrigin() {
            assertThrows(BadRequestException.class,
                    () -> validator.validate(TransactionType.FLIGHT, json("{\"destination\":\"LHR\"}")));
        }
    }

    @Nested
    class Hotel {
        @Test
        void acceptsRequiredFields() throws Exception {
            validator.validate(TransactionType.HOTEL,
                    json("{\"name\":\"X\",\"check_in\":\"2026-03-11\",\"check_out\":\"2026-03-13\"}"));
        }

        @Test
        void rejectsMissingCheckout() {
            assertThrows(BadRequestException.class, () -> validator.validate(TransactionType.HOTEL,
                    json("{\"name\":\"X\",\"check_in\":\"2026-03-11\"}")));
        }
    }

    @Nested
    class Rail {
        @Test
        void acceptsNonEmptyLegs() throws Exception {
            validator.validate(TransactionType.RAIL, json("{\"legs\":[{\"origin\":\"A\"}]}"));
        }

        @Test
        void rejectsEmptyLegs() {
            assertThrows(BadRequestException.class,
                    () -> validator.validate(TransactionType.RAIL, json("{\"legs\":[]}")));
        }

        @Test
        void rejectsMissingLegs() {
            assertThrows(BadRequestException.class,
                    () -> validator.validate(TransactionType.RAIL, json("{}")));
        }
    }

    @Nested
    class NavanFee {
        @Test
        void acceptsTripFee() throws Exception {
            validator.validate(TransactionType.NAVAN_FEE, json("{\"fee_kind\":\"trip_fee\"}"));
        }

        @Test
        void acceptsAgentCallFee() throws Exception {
            validator.validate(TransactionType.NAVAN_FEE, json("{\"fee_kind\":\"agent_call_fee\"}"));
        }

        @Test
        void rejectsUnknownKind() {
            assertThrows(BadRequestException.class,
                    () -> validator.validate(TransactionType.NAVAN_FEE, json("{\"fee_kind\":\"other\"}")));
        }

        @Test
        void rejectsMissingKind() {
            assertThrows(BadRequestException.class,
                    () -> validator.validate(TransactionType.NAVAN_FEE, json("{}")));
        }
    }

    @Test
    void rejectsNonObjectMetadata() {
        assertThrows(BadRequestException.class,
                () -> validator.validate(TransactionType.FLIGHT, json("[]")));
    }
}
