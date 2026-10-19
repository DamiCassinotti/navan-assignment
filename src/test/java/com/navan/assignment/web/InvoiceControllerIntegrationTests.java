package com.navan.assignment.web;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navan.assignment.transaction.TransactionRepository;
import com.navan.assignment.transaction.model.FeeLine;
import com.navan.assignment.transaction.model.TaxLine;
import com.navan.assignment.transaction.model.Transaction;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class InvoiceControllerIntegrationTests {

    private static Path dbFile;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        dbFile = Files.createTempFile("navan-invoice-it-", ".db");
        Files.deleteIfExists(dbFile);
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile.toAbsolutePath());
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (dbFile != null) Files.deleteIfExists(dbFile);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DSLContext dsl;
    @Autowired TransactionRepository transactionRepository;

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

    private Map<String, String> postSeeds(String... keys) throws Exception {
        JsonNode seeds = mapper.readTree(getClass().getResourceAsStream("/fixtures/seed-payloads.json"));
        Map<String, String> ids = new LinkedHashMap<>();
        for (String key : keys) {
            String body = mapper.writeValueAsString(seeds.get(key));
            MvcResult result = mvc.perform(post("/transactions")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andReturn();
            ids.put(key, mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
        }
        return ids;
    }

    @Nested
    class TripInvoice {

        @Test
        void grandTotalIs685_70AndRollupsMatch() throws Exception {
            Map<String, String> ids = postSeeds("flight", "hotel", "rail", "trip_fee", "agent_call_fee");

            ObjectNode body = mapper.createObjectNode();
            body.putArray("transaction_ids")
                    .add(ids.get("flight"))
                    .add(ids.get("hotel"))
                    .add(ids.get("rail"))
                    .add(ids.get("trip_fee"))
                    .add(ids.get("agent_call_fee"));

            MvcResult posted = mvc.perform(post("/invoices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.currency").value("EUR"))
                    .andExpect(jsonPath("$.grand_total").value(685.70))
                    .andExpect(jsonPath("$.lines", hasSize(5)))
                    .andExpect(jsonPath("$.tax_rollup", hasSize(2)))
                    .andExpect(jsonPath("$.fee_rollup", hasSize(2)))
                    .andReturn();

            String invoiceId = mapper.readTree(posted.getResponse().getContentAsString())
                    .get("id").asText();

            mvc.perform(get("/invoices/{id}", invoiceId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.grand_total").value(685.70))
                    .andExpect(jsonPath("$.lines", hasSize(5)))
                    .andExpect(jsonPath("$.tax_rollup[?(@.name=='Airport tax')].amount").value(contains(15.0)))
                    .andExpect(jsonPath("$.tax_rollup[?(@.name=='VAT')].rate").value(contains(0.07)))
                    .andExpect(jsonPath("$.tax_rollup[?(@.name=='VAT')].amount").value(contains(21.7)))
                    .andExpect(jsonPath("$.fee_rollup[?(@.name=='YQ')].amount").value(contains(25.0)))
                    .andExpect(jsonPath("$.fee_rollup[?(@.name=='Booking fee')].amount").value(contains(4.0)));
        }
    }

    @Nested
    class MixedCurrency {

        @Test
        void mixedCurrencyReturns409() throws Exception {
            Map<String, String> ids = postSeeds("flight");

            JsonNode seeds = mapper.readTree(getClass().getResourceAsStream("/fixtures/seed-payloads.json"));
            ObjectNode usdHotel = ((ObjectNode) seeds.get("hotel").deepCopy())
                    .put("currency", "USD")
                    .put("external_id", "hotel-USD-mix");
            MvcResult usdResult = mvc.perform(post("/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(usdHotel)))
                    .andExpect(status().isCreated())
                    .andReturn();
            String usdId = mapper.readTree(usdResult.getResponse().getContentAsString())
                    .get("id").asText();

            ObjectNode body = mapper.createObjectNode();
            body.putArray("transaction_ids").add(ids.get("flight")).add(usdId);

            mvc.perform(post("/invoices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isConflict());

            assertEquals(0, dsl.fetchCount(DSL.table("invoices")));
            assertEquals(0, dsl.fetchCount(DSL.table("invoice_lines")));
        }
    }

    @Nested
    class RepeatedIdDedupe {

        @Test
        void duplicateIdsCollapseToSingleLine() throws Exception {
            Map<String, String> ids = postSeeds("flight", "hotel");

            ObjectNode body = mapper.createObjectNode();
            body.putArray("transaction_ids")
                    .add(ids.get("flight"))
                    .add(ids.get("flight"))
                    .add(ids.get("hotel"))
                    .add(ids.get("flight"));

            mvc.perform(post("/invoices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.lines", hasSize(2)))
                    .andExpect(jsonPath("$.grand_total").value(551.70));
        }

        @Test
        void singleAndArrayCombinedDedupes() throws Exception {
            Map<String, String> ids = postSeeds("flight");

            ObjectNode body = mapper.createObjectNode();
            body.put("transaction_id", ids.get("flight"));
            body.putArray("transaction_ids").add(ids.get("flight"));

            mvc.perform(post("/invoices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.lines", hasSize(1)))
                    .andExpect(jsonPath("$.grand_total").value(220.0));
        }
    }

    @Nested
    class Pagination {

        @Test
        void defaultsReturnAllInvoicesWithoutMore() throws Exception {
            Map<String, String> ids = postSeeds("flight", "hotel");
            createInvoice(ids.get("flight"));
            createInvoice(ids.get("hotel"));

            mvc.perform(get("/invoices"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.limit").value(50))
                    .andExpect(jsonPath("$.offset").value(0))
                    .andExpect(jsonPath("$.has_more").value(false));
        }

        @Test
        void limitAndOffsetPageThroughResults() throws Exception {
            Map<String, String> ids = postSeeds("flight", "hotel", "rail");
            String firstInvoice = createInvoice(ids.get("flight"));
            String secondInvoice = createInvoice(ids.get("hotel"));
            String thirdInvoice = createInvoice(ids.get("rail"));

            MvcResult firstPage = mvc.perform(get("/invoices").param("limit", "2").param("offset", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.limit").value(2))
                    .andExpect(jsonPath("$.offset").value(0))
                    .andExpect(jsonPath("$.has_more").value(true))
                    .andReturn();

            JsonNode firstItems = mapper.readTree(firstPage.getResponse().getContentAsString()).get("items");
            assertEquals(firstInvoice, firstItems.get(0).get("id").asText());
            assertEquals(secondInvoice, firstItems.get(1).get("id").asText());

            MvcResult secondPage = mvc.perform(get("/invoices").param("limit", "2").param("offset", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.has_more").value(false))
                    .andReturn();

            JsonNode secondItems = mapper.readTree(secondPage.getResponse().getContentAsString()).get("items");
            assertEquals(thirdInvoice, secondItems.get(0).get("id").asText());
        }

        @Test
        void limitAbove100IsRejected() throws Exception {
            mvc.perform(get("/invoices").param("limit", "101"))
                    .andExpect(status().isBadRequest());
        }

        private String createInvoice(String transactionId) throws Exception {
            ObjectNode body = mapper.createObjectNode();
            body.put("transaction_id", transactionId);
            MvcResult result = mvc.perform(post("/invoices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andReturn();
            return mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        }
    }

    @Nested
    class SnapshotImmutability {

        @Test
        void mutatingUnderlyingTransactionDoesNotAffectInvoice() throws Exception {
            Map<String, String> ids = postSeeds("flight", "hotel");

            ObjectNode body = mapper.createObjectNode();
            body.putArray("transaction_ids").add(ids.get("flight")).add(ids.get("hotel"));

            MvcResult posted = mvc.perform(post("/invoices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andReturn();

            JsonNode created = mapper.readTree(posted.getResponse().getContentAsString());
            String invoiceId = created.get("id").asText();
            double originalGrandTotal = created.get("grand_total").asDouble();
            JsonNode originalTaxRollup = created.get("tax_rollup");
            JsonNode originalFeeRollup = created.get("fee_rollup");

            UUID flightId = UUID.fromString(ids.get("flight"));
            Transaction flight = transactionRepository.findById(flightId).orElseThrow();
            Transaction mutated = new Transaction(
                    flight.id(),
                    flight.externalId(),
                    flight.type(),
                    flight.currency(),
                    flight.occurredAt(),
                    new BigDecimal("9999.99"),
                    flight.metadata(),
                    List.of(new TaxLine("Mutated tax", new BigDecimal("0.5"), new BigDecimal("1234.56"))),
                    List.of(new FeeLine("Mutated fee", new BigDecimal("777.00"))),
                    flight.createdAt(),
                    Instant.now()
            );
            transactionRepository.updateHeader(mutated);
            transactionRepository.deleteLines(flightId);
            transactionRepository.insertLines(mutated);

            mvc.perform(get("/invoices/{id}", invoiceId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.grand_total").value(originalGrandTotal))
                    .andExpect(jsonPath("$.tax_rollup").value(mapper.convertValue(originalTaxRollup, List.class)))
                    .andExpect(jsonPath("$.fee_rollup").value(mapper.convertValue(originalFeeRollup, List.class)))
                    .andExpect(jsonPath("$.lines[?(@.transaction_id=='" + ids.get("flight") + "')].total")
                            .value(contains(220.0)))
                    .andExpect(jsonPath("$.lines[?(@.transaction_id=='" + ids.get("flight") + "')].tax_lines[0].name")
                            .value(contains("Airport tax")))
                    .andExpect(jsonPath("$.lines[?(@.transaction_id=='" + ids.get("flight") + "')].fee_lines[0].name")
                            .value(contains("YQ")));
        }
    }

    @Nested
    class MissingTransaction {

        @Test
        void unknownTransactionIdReturns404() throws Exception {
            Map<String, String> ids = postSeeds("flight");

            ObjectNode body = mapper.createObjectNode();
            body.putArray("transaction_ids")
                    .add(ids.get("flight"))
                    .add(UUID.randomUUID().toString());

            mvc.perform(post("/invoices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound());

            assertEquals(0, dsl.fetchCount(DSL.table("invoices")));
            assertEquals(0, dsl.fetchCount(DSL.table("invoice_lines")));
        }
    }

    @Nested
    class UpdateInvoice {

        @Test
        void reSnapshotsAtomicallyAndSwapsLines() throws Exception {
            Map<String, String> ids = postSeeds("flight", "hotel", "rail");

            ObjectNode body = mapper.createObjectNode();
            body.putArray("transaction_ids").add(ids.get("flight")).add(ids.get("hotel"));
            MvcResult posted = mvc.perform(post("/invoices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andReturn();
            JsonNode created = mapper.readTree(posted.getResponse().getContentAsString());
            String invoiceId = created.get("id").asText();
            String createdAt = created.get("created_at").asText();

            ObjectNode patch = mapper.createObjectNode();
            patch.putArray("transaction_ids").add(ids.get("hotel")).add(ids.get("rail"));
            mvc.perform(patch("/invoices/{id}", invoiceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(patch)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(invoiceId))
                    .andExpect(jsonPath("$.created_at").value(createdAt))
                    .andExpect(jsonPath("$.lines", hasSize(2)))
                    .andExpect(jsonPath("$.grand_total").value(425.70));

            mvc.perform(get("/invoices/{id}", invoiceId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lines", hasSize(2)))
                    .andExpect(jsonPath("$.lines[*].transaction_id",
                            containsInAnyOrder(ids.get("hotel"), ids.get("rail"))))
                    .andExpect(jsonPath("$.grand_total").value(425.70));

            assertEquals(1, dsl.fetchCount(DSL.table("invoices")));
            assertEquals(2, dsl.fetchCount(DSL.table("invoice_lines")));
        }

        @Test
        void unknownInvoiceReturns404() throws Exception {
            Map<String, String> ids = postSeeds("flight");
            ObjectNode body = mapper.createObjectNode();
            body.put("transaction_id", ids.get("flight"));
            mvc.perform(patch("/invoices/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void mixedCurrencyReturns409AndLeavesInvoiceUnchanged() throws Exception {
            Map<String, String> ids = postSeeds("flight");

            ObjectNode body = mapper.createObjectNode();
            body.put("transaction_id", ids.get("flight"));
            MvcResult posted = mvc.perform(post("/invoices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andReturn();
            String invoiceId = mapper.readTree(posted.getResponse().getContentAsString())
                    .get("id").asText();

            JsonNode seeds = mapper.readTree(getClass().getResourceAsStream("/fixtures/seed-payloads.json"));
            ObjectNode usdHotel = ((ObjectNode) seeds.get("hotel").deepCopy())
                    .put("currency", "USD")
                    .put("external_id", "hotel-USD-patch");
            MvcResult usdResult = mvc.perform(post("/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(usdHotel)))
                    .andExpect(status().isCreated())
                    .andReturn();
            String usdId = mapper.readTree(usdResult.getResponse().getContentAsString())
                    .get("id").asText();

            ObjectNode patch = mapper.createObjectNode();
            patch.putArray("transaction_ids").add(ids.get("flight")).add(usdId);
            mvc.perform(patch("/invoices/{id}", invoiceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(patch)))
                    .andExpect(status().isConflict());

            mvc.perform(get("/invoices/{id}", invoiceId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.grand_total").value(220.0))
                    .andExpect(jsonPath("$.lines", hasSize(1)));
        }
    }

    @Nested
    class DeleteInvoice {

        @Test
        void dropsInvoiceAndSnapshotRowsOnly() throws Exception {
            Map<String, String> ids = postSeeds("flight", "hotel");

            ObjectNode body = mapper.createObjectNode();
            body.putArray("transaction_ids").add(ids.get("flight")).add(ids.get("hotel"));
            MvcResult posted = mvc.perform(post("/invoices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andReturn();
            String invoiceId = mapper.readTree(posted.getResponse().getContentAsString())
                    .get("id").asText();

            mvc.perform(delete("/invoices/{id}", invoiceId))
                    .andExpect(status().isNoContent());

            mvc.perform(get("/invoices/{id}", invoiceId))
                    .andExpect(status().isNotFound());

            assertEquals(0, dsl.fetchCount(DSL.table("invoices")));
            assertEquals(0, dsl.fetchCount(DSL.table("invoice_lines")));
            assertEquals(0, dsl.fetchCount(DSL.table("invoice_tax_lines")));
            assertEquals(0, dsl.fetchCount(DSL.table("invoice_fee_lines")));

            assertEquals(2, dsl.fetchCount(DSL.table("transactions")));
            mvc.perform(get("/transactions/{id}", ids.get("flight")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ids.get("flight")))
                    .andExpect(jsonPath("$.total").value(220.0));
            mvc.perform(get("/transactions/{id}", ids.get("hotel")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ids.get("hotel")))
                    .andExpect(jsonPath("$.total").value(331.70));
        }

        @Test
        void unknownIdReturns404() throws Exception {
            mvc.perform(delete("/invoices/{id}", UUID.randomUUID()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void transactionBecomesPatchableAgainAfterInvoiceDelete() throws Exception {
            Map<String, String> ids = postSeeds("flight");

            ObjectNode body = mapper.createObjectNode();
            body.put("transaction_id", ids.get("flight"));
            MvcResult posted = mvc.perform(post("/invoices")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andReturn();
            String invoiceId = mapper.readTree(posted.getResponse().getContentAsString())
                    .get("id").asText();

            mvc.perform(delete("/invoices/{id}", invoiceId))
                    .andExpect(status().isNoContent());

            mvc.perform(delete("/transactions/{id}", ids.get("flight")))
                    .andExpect(status().isNoContent());
        }
    }
}
