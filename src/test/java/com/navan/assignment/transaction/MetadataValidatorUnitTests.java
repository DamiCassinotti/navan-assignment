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

    private static final String VALID_FLIGHT = """
            {
              "origin":"TXL","destination":"LHR","fare":180.0,
              "coupons":[{"flight_number":"BA982","from":"TXL","to":"LHR",
                          "departure":"2026-03-11T07:00:00Z","arrival":"2026-03-11T08:20:00Z"}]
            }
            """;

    private static final String VALID_HOTEL = """
            {
              "name":"X",
              "address":{"street":"12 Kingsway","city":"London","country":"GB"},
              "check_in":"2026-03-11","check_out":"2026-03-13",
              "nights":2,"room_rate_per_night":140.0
            }
            """;

    private static final String VALID_RAIL = """
            {
              "legs":[{"origin":"A","destination":"B",
                       "departure":"2026-03-13T09:00:00Z","arrival":"2026-03-13T10:30:00Z"}]
            }
            """;

    @Nested
    class Flight {
        @Test
        void acceptsFullSpec() throws Exception {
            validator.validate(TransactionType.FLIGHT, json(VALID_FLIGHT));
        }

        @Test
        void acceptsEmptyCouponsArray() throws Exception {
            validator.validate(TransactionType.FLIGHT, json("""
                    {"origin":"TXL","destination":"LHR","fare":180.0,"coupons":[]}"""));
        }

        @Test
        void rejectsMissingOrigin() {
            assertThrows(BadRequestException.class, () -> validator.validate(TransactionType.FLIGHT, json("""
                    {"destination":"LHR","fare":180.0,"coupons":[]}""")));
        }

        @Test
        void rejectsMissingFare() {
            assertThrows(BadRequestException.class, () -> validator.validate(TransactionType.FLIGHT, json("""
                    {"origin":"TXL","destination":"LHR","coupons":[]}""")));
        }

        @Test
        void rejectsMissingCouponsArray() {
            assertThrows(BadRequestException.class, () -> validator.validate(TransactionType.FLIGHT, json("""
                    {"origin":"TXL","destination":"LHR","fare":180.0}""")));
        }

        @Test
        void rejectsCouponMissingFlightNumber() {
            assertThrows(BadRequestException.class, () -> validator.validate(TransactionType.FLIGHT, json("""
                    {"origin":"TXL","destination":"LHR","fare":180.0,
                     "coupons":[{"from":"TXL","to":"LHR",
                                 "departure":"2026-03-11T07:00:00Z","arrival":"2026-03-11T08:20:00Z"}]}""")));
        }
    }

    @Nested
    class Hotel {
        @Test
        void acceptsFullSpec() throws Exception {
            validator.validate(TransactionType.HOTEL, json(VALID_HOTEL));
        }

        @Test
        void rejectsMissingAddress() {
            assertThrows(BadRequestException.class, () -> validator.validate(TransactionType.HOTEL, json("""
                    {"name":"X","check_in":"2026-03-11","check_out":"2026-03-13",
                     "nights":2,"room_rate_per_night":140.0}""")));
        }

        @Test
        void rejectsAddressMissingCity() {
            assertThrows(BadRequestException.class, () -> validator.validate(TransactionType.HOTEL, json("""
                    {"name":"X","address":{"street":"12 Kingsway","country":"GB"},
                     "check_in":"2026-03-11","check_out":"2026-03-13",
                     "nights":2,"room_rate_per_night":140.0}""")));
        }

        @Test
        void rejectsMissingNights() {
            assertThrows(BadRequestException.class, () -> validator.validate(TransactionType.HOTEL, json("""
                    {"name":"X","address":{"street":"s","city":"c","country":"GB"},
                     "check_in":"2026-03-11","check_out":"2026-03-13","room_rate_per_night":140.0}""")));
        }
    }

    @Nested
    class Rail {
        @Test
        void acceptsLegsWithFullSpec() throws Exception {
            validator.validate(TransactionType.RAIL, json(VALID_RAIL));
        }

        @Test
        void rejectsLegMissingArrival() {
            assertThrows(BadRequestException.class, () -> validator.validate(TransactionType.RAIL, json("""
                    {"legs":[{"origin":"A","destination":"B","departure":"2026-03-13T09:00:00Z"}]}""")));
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
