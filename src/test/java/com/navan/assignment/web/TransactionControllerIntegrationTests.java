package com.navan.assignment.web;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionControllerIntegrationTests {

    private static Path dbFile;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        dbFile = Files.createTempFile("navan-it-", ".db");
        Files.deleteIfExists(dbFile);
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile.toAbsolutePath()
                + "?foreign_keys=on&journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL");
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (dbFile != null) Files.deleteIfExists(dbFile);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DSLContext dsl;

    private static final String FLIGHT_METADATA_LHR =
            "{\"origin\":\"TXL\",\"destination\":\"LHR\",\"fare\":180.0,"
          + "\"coupons\":[{\"flight_number\":\"BA982\",\"from\":\"TXL\",\"to\":\"LHR\","
          + "\"departure\":\"2026-03-11T07:00:00Z\",\"arrival\":\"2026-03-11T08:20:00Z\"}]}";

    private static final String FLIGHT_METADATA_CDG =
            "{\"origin\":\"TXL\",\"destination\":\"CDG\",\"fare\":300.0,"
          + "\"coupons\":[{\"flight_number\":\"AF123\",\"from\":\"TXL\",\"to\":\"CDG\","
          + "\"departure\":\"2026-04-01T10:00:00Z\",\"arrival\":\"2026-04-01T11:30:00Z\"}]}";

    @BeforeEach
    void clean() {
        dsl.execute("DELETE FROM invoice_fee_lines");
        dsl.execute("DELETE FROM invoice_tax_lines");
        dsl.execute("DELETE FROM invoice_lines");
        dsl.execute("DELETE FROM invoices");
        dsl.execute("DELETE FROM fee_lines");
        dsl.execute("DELETE FROM tax_lines");
        dsl.execute("DELETE FROM transactions");
    }

    @Nested
    class CreateAllFiveTypes {

        @Test
        void postEachFixtureAndGetRoundTrips() throws Exception {
            JsonNode seeds = mapper.readTree(
                    getClass().getResourceAsStream("/fixtures/seed-payloads.json"));

            Iterator<Map.Entry<String, JsonNode>> fixtures = seeds.fields();
            while (fixtures.hasNext()) {
                Map.Entry<String, JsonNode> entry = fixtures.next();
                if (entry.getKey().equals("duplicate_flight")) continue;
                JsonNode payload = entry.getValue();
                String body = mapper.writeValueAsString(payload);

                MvcResult postResult = mvc.perform(post("/transactions")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.id").isString())
                        .andExpect(jsonPath("$.external_id").value(payload.get("external_id").asText()))
                        .andExpect(jsonPath("$.type").value(payload.get("type").asText()))
                        .andReturn();

                JsonNode created = mapper.readTree(postResult.getResponse().getContentAsString());
                String id = created.get("id").asText();

                var get = mvc.perform(get("/transactions/{id}", id))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.external_id").value(payload.get("external_id").asText()))
                        .andExpect(jsonPath("$.currency").value(payload.get("currency").asText()))
                        .andExpect(jsonPath("$.total").value(payload.get("total").decimalValue().doubleValue()))
                        .andExpect(jsonPath("$.metadata").exists())
                        .andExpect(jsonPath("$.tax_lines", hasSize(payload.get("tax_lines").size())))
                        .andExpect(jsonPath("$.fee_lines", hasSize(payload.get("fee_lines").size())));

                switch (payload.get("type").asText()) {
                    case "flight" -> get
                            .andExpect(jsonPath("$.metadata.origin").value(payload.at("/metadata/origin").asText()))
                            .andExpect(jsonPath("$.metadata.destination").value(payload.at("/metadata/destination").asText()))
                            .andExpect(jsonPath("$.metadata.coupons", hasSize(payload.at("/metadata/coupons").size())))
                            .andExpect(jsonPath("$.metadata.coupons[0].flight_number")
                                    .value(payload.at("/metadata/coupons/0/flight_number").asText()));
                    case "hotel" -> get
                            .andExpect(jsonPath("$.metadata.name").value(payload.at("/metadata/name").asText()))
                            .andExpect(jsonPath("$.metadata.address.street").value(payload.at("/metadata/address/street").asText()))
                            .andExpect(jsonPath("$.metadata.address.city").value(payload.at("/metadata/address/city").asText()))
                            .andExpect(jsonPath("$.metadata.address.country").value(payload.at("/metadata/address/country").asText()))
                            .andExpect(jsonPath("$.metadata.nights").value(payload.at("/metadata/nights").asInt()));
                    case "rail" -> get
                            .andExpect(jsonPath("$.metadata.legs", hasSize(payload.at("/metadata/legs").size())))
                            .andExpect(jsonPath("$.metadata.legs[0].origin").value(payload.at("/metadata/legs/0/origin").asText()))
                            .andExpect(jsonPath("$.metadata.legs[0].destination").value(payload.at("/metadata/legs/0/destination").asText()));
                    case "navan_fee" -> get
                            .andExpect(jsonPath("$.metadata.fee_kind").value(payload.at("/metadata/fee_kind").asText()));
                    default -> {}
                }
            }
        }
    }

    @Nested
    class DuplicateExternalId {

        @Test
        void secondPostReturns200WithSameId() throws Exception {
            JsonNode seeds = mapper.readTree(
                    getClass().getResourceAsStream("/fixtures/seed-payloads.json"));
            String flight = mapper.writeValueAsString(seeds.get("flight"));
            String duplicate = mapper.writeValueAsString(seeds.get("duplicate_flight"));

            MvcResult firstResponse = mvc.perform(post("/transactions")
                            .contentType(MediaType.APPLICATION_JSON).content(flight))
                    .andExpect(status().isCreated())
                    .andReturn();
            String firstId = mapper.readTree(firstResponse.getResponse().getContentAsString())
                    .get("id").asText();

            mvc.perform(post("/transactions")
                            .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(firstId));

            int rowCount = dsl.fetchCount(
                    DSL.table("transactions"),
                    DSL.field("external_id").eq(
                            seeds.get("flight").get("external_id").asText()));
            assertEquals(1, rowCount);
        }

        @Test
        void concurrentPostsCollapseToSingleRow() throws Exception {
            JsonNode seeds = mapper.readTree(
                    getClass().getResourceAsStream("/fixtures/seed-payloads.json"));
            String flight = mapper.writeValueAsString(seeds.get("flight"));
            String externalId = seeds.get("flight").get("external_id").asText();

            int threads = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    MvcResult result = mvc.perform(post("/transactions")
                                    .contentType(MediaType.APPLICATION_JSON).content(flight))
                            .andReturn();
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 200 || status == 201,
                            "unexpected status " + status + ": " + result.getResponse().getContentAsString());
                    return mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
                }));
            }

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            Set<String> ids = new HashSet<>();
            for (Future<String> future : futures) ids.add(future.get(30, TimeUnit.SECONDS));
            pool.shutdown();

            assertEquals(1, ids.size(),
                    "all concurrent POSTs should return the same id, got " + ids);

            int rowCount = dsl.fetchCount(
                    DSL.table("transactions"),
                    DSL.field("external_id").eq(externalId));
            assertEquals(1, rowCount);
        }
    }

    @Nested
    class Pagination {

        @Test
        void listsWithDefaultLimit50AndRespectsOffset() throws Exception {
            JsonNode seeds = mapper.readTree(
                    getClass().getResourceAsStream("/fixtures/seed-payloads.json"));
            JsonNode base = seeds.get("flight");

            int count = 55;
            for (int i = 0; i < count; i++) {
                ObjectNode copy = ((ObjectNode) base.deepCopy())
                        .put("external_id", "page-flight-" + i);
                mvc.perform(post("/transactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(copy)))
                        .andExpect(status().isCreated());
            }

            mvc.perform(get("/transactions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(50)))
                    .andExpect(jsonPath("$.limit").value(50))
                    .andExpect(jsonPath("$.offset").value(0))
                    .andExpect(jsonPath("$.has_more").value(true));

            mvc.perform(get("/transactions").param("limit", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(count)))
                    .andExpect(jsonPath("$.has_more").value(false));

            mvc.perform(get("/transactions").param("limit", "10").param("offset", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(count - 50)))
                    .andExpect(jsonPath("$.offset").value(50))
                    .andExpect(jsonPath("$.has_more").value(false));

            mvc.perform(get("/transactions").param("limit", "10").param("offset", "1000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(0)))
                    .andExpect(jsonPath("$.has_more").value(false));
        }

        @Test
        void hasMoreTrueWhenExactlyOneMoreRowExists() throws Exception {
            JsonNode seeds = mapper.readTree(
                    getClass().getResourceAsStream("/fixtures/seed-payloads.json"));
            JsonNode base = seeds.get("flight");
            for (int i = 0; i < 3; i++) {
                ObjectNode copy = ((ObjectNode) base.deepCopy())
                        .put("external_id", "hm-flight-" + i);
                mvc.perform(post("/transactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(copy)))
                        .andExpect(status().isCreated());
            }

            mvc.perform(get("/transactions").param("limit", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.has_more").value(true));

            mvc.perform(get("/transactions").param("limit", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(3)))
                    .andExpect(jsonPath("$.has_more").value(false));
        }

        @Test
        void limitAboveHundredRejectedWith400() throws Exception {
            mvc.perform(get("/transactions").param("limit", "101"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void limitBelowOneRejectedWith400() throws Exception {
            mvc.perform(get("/transactions").param("limit", "0"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class UpdateTransaction {

        @Test
        void patchReplacesHeaderAndLinesAtomically() throws Exception {
            JsonNode seeds = mapper.readTree(
                    getClass().getResourceAsStream("/fixtures/seed-payloads.json"));
            String flight = mapper.writeValueAsString(seeds.get("flight"));
            MvcResult created = mvc.perform(post("/transactions")
                            .contentType(MediaType.APPLICATION_JSON).content(flight))
                    .andExpect(status().isCreated())
                    .andReturn();
            String id = mapper.readTree(created.getResponse().getContentAsString())
                    .get("id").asText();
            String originalExternalId = seeds.get("flight").get("external_id").asText();

            String patch = "{\"type\":\"flight\",\"currency\":\"USD\","
                    + "\"occurred_at\":\"2026-04-01T10:00:00Z\",\"total\":333.33,"
                    + "\"metadata\":" + FLIGHT_METADATA_CDG + ","
                    + "\"tax_lines\":[{\"name\":\"VAT\",\"rate\":0.1,\"amount\":30.30}],"
                    + "\"fee_lines\":[{\"name\":\"booking\",\"amount\":5.00}]}";

            mvc.perform(patch("/transactions/{id}", id)
                            .contentType(MediaType.APPLICATION_JSON).content(patch))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.external_id").value(originalExternalId))
                    .andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.total").value(333.33))
                    .andExpect(jsonPath("$.metadata.destination").value("CDG"))
                    .andExpect(jsonPath("$.tax_lines", hasSize(1)))
                    .andExpect(jsonPath("$.tax_lines[0].name").value("VAT"))
                    .andExpect(jsonPath("$.fee_lines", hasSize(1)))
                    .andExpect(jsonPath("$.fee_lines[0].name").value("booking"));

            mvc.perform(get("/transactions/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.tax_lines", hasSize(1)))
                    .andExpect(jsonPath("$.tax_lines[0].name").value("VAT"))
                    .andExpect(jsonPath("$.fee_lines", hasSize(1)));

            int taxRows = dsl.fetchCount(DSL.table("tax_lines"));
            int feeRows = dsl.fetchCount(DSL.table("fee_lines"));
            assertEquals(1, taxRows);
            assertEquals(1, feeRows);
        }

        @Test
        void patchWithEmptyLinesClearsThem() throws Exception {
            JsonNode seeds = mapper.readTree(
                    getClass().getResourceAsStream("/fixtures/seed-payloads.json"));
            String flight = mapper.writeValueAsString(seeds.get("flight"));
            MvcResult created = mvc.perform(post("/transactions")
                            .contentType(MediaType.APPLICATION_JSON).content(flight))
                    .andExpect(status().isCreated())
                    .andReturn();
            String id = mapper.readTree(created.getResponse().getContentAsString())
                    .get("id").asText();

            String patch = "{\"type\":\"flight\",\"currency\":\"EUR\","
                    + "\"occurred_at\":\"2026-03-11T06:40:00Z\",\"total\":220.00,"
                    + "\"metadata\":" + FLIGHT_METADATA_LHR + ","
                    + "\"tax_lines\":[],\"fee_lines\":[]}";
            mvc.perform(patch("/transactions/{id}", id)
                            .contentType(MediaType.APPLICATION_JSON).content(patch))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tax_lines", hasSize(0)))
                    .andExpect(jsonPath("$.fee_lines", hasSize(0)));

            assertEquals(0, dsl.fetchCount(DSL.table("tax_lines")));
            assertEquals(0, dsl.fetchCount(DSL.table("fee_lines")));
        }

        @Test
        void patchUnknownIdReturns404() throws Exception {
            String patch = "{\"type\":\"flight\",\"currency\":\"EUR\","
                    + "\"occurred_at\":\"2026-03-11T06:40:00Z\",\"total\":10,"
                    + "\"metadata\":" + FLIGHT_METADATA_LHR + ","
                    + "\"tax_lines\":[],\"fee_lines\":[]}";
            mvc.perform(patch("/transactions/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON).content(patch))
                    .andExpect(status().isNotFound());
        }

        @Test
        void patchWithBadMetadataReturns400AndLeavesRowUnchanged() throws Exception {
            JsonNode seeds = mapper.readTree(
                    getClass().getResourceAsStream("/fixtures/seed-payloads.json"));
            String flight = mapper.writeValueAsString(seeds.get("flight"));
            MvcResult created = mvc.perform(post("/transactions")
                            .contentType(MediaType.APPLICATION_JSON).content(flight))
                    .andExpect(status().isCreated())
                    .andReturn();
            String id = mapper.readTree(created.getResponse().getContentAsString())
                    .get("id").asText();

            String bad = """
                {"type":"navan_fee","currency":"EUR",
                 "occurred_at":"2026-03-11T06:40:00Z","total":10,
                 "metadata":{"fee_kind":"nope"},
                 "tax_lines":[],"fee_lines":[]}
                """;
            mvc.perform(patch("/transactions/{id}", id)
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());

            mvc.perform(get("/transactions/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.currency").value("EUR"))
                    .andExpect(jsonPath("$.type").value("flight"));
        }
    }

    @Nested
    class DeleteTransaction {

        @Test
        void deleteRemovesTransactionAndCascadesLines() throws Exception {
            JsonNode seeds = mapper.readTree(
                    getClass().getResourceAsStream("/fixtures/seed-payloads.json"));
            String flight = mapper.writeValueAsString(seeds.get("flight"));
            MvcResult created = mvc.perform(post("/transactions")
                            .contentType(MediaType.APPLICATION_JSON).content(flight))
                    .andExpect(status().isCreated())
                    .andReturn();
            String id = mapper.readTree(created.getResponse().getContentAsString())
                    .get("id").asText();

            assertTrue(dsl.fetchCount(DSL.table("tax_lines")) > 0
                    || dsl.fetchCount(DSL.table("fee_lines")) > 0);

            mvc.perform(delete("/transactions/{id}", id))
                    .andExpect(status().isNoContent());

            mvc.perform(get("/transactions/{id}", id))
                    .andExpect(status().isNotFound());

            assertEquals(0, dsl.fetchCount(DSL.table("transactions")));
            assertEquals(0, dsl.fetchCount(DSL.table("tax_lines")));
            assertEquals(0, dsl.fetchCount(DSL.table("fee_lines")));
        }

        @Test
        void deleteUnknownIdReturns404() throws Exception {
            mvc.perform(delete("/transactions/{id}", UUID.randomUUID()))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class Validation {

        @Test
        void rejectsNavanFeeWithBadFeeKind() throws Exception {
            String bad = """
                {"type":"navan_fee","external_id":"bad-navan-1","currency":"EUR",
                 "occurred_at":"2026-03-11T06:40:00Z","total":10,
                 "metadata":{"fee_kind":"nope"},
                 "tax_lines":[],"fee_lines":[]}
                """;
            mvc.perform(post("/transactions")
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void rejectsMissingExternalId() throws Exception {
            String bad = """
                {"type":"flight","currency":"EUR",
                 "occurred_at":"2026-03-11T06:40:00Z","total":10,
                 "metadata":{"origin":"A","destination":"B"},
                 "tax_lines":[],"fee_lines":[]}
                """;
            mvc.perform(post("/transactions")
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void getUnknownIdReturns404() throws Exception {
            mvc.perform(get("/transactions/{id}", UUID.randomUUID()))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class InvoicedGuard {

        @Test
        void patchRejectedWhenTransactionIsOnInvoice() throws Exception {
            String flightId = postSeed("flight");
            invoiceOn(flightId);

            JsonNode seeds = mapper.readTree(
                    getClass().getResourceAsStream("/fixtures/seed-payloads.json"));
            ObjectNode patch = (ObjectNode) seeds.get("flight").deepCopy();
            patch.put("total", 999.99);
            patch.remove("external_id");

            mvc.perform(patch("/transactions/{id}", flightId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(patch)))
                    .andExpect(status().isConflict());

            mvc.perform(get("/transactions/{id}", flightId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(220.0));
        }

        @Test
        void deleteRejectedWhenTransactionIsOnInvoice() throws Exception {
            String flightId = postSeed("flight");
            invoiceOn(flightId);

            mvc.perform(delete("/transactions/{id}", flightId))
                    .andExpect(status().isConflict());

            mvc.perform(get("/transactions/{id}", flightId))
                    .andExpect(status().isOk());
            assertEquals(1, dsl.fetchCount(DSL.table("transactions")));
        }

        private String postSeed(String key) throws Exception {
            JsonNode seeds = mapper.readTree(
                    getClass().getResourceAsStream("/fixtures/seed-payloads.json"));
            MvcResult result = mvc.perform(post("/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(seeds.get(key))))
                    .andExpect(status().isCreated())
                    .andReturn();
            return mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        }

        private void invoiceOn(String transactionId) throws Exception {
            ObjectNode body = mapper.createObjectNode();
            body.put("transaction_id", transactionId);
            mvc.perform(post("/invoices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isCreated());
        }
    }
}
