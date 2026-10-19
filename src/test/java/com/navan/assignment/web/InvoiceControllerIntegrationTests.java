package com.navan.assignment.web;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
}
