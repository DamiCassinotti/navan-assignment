package com.navan.assignment.web;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

                mvc.perform(get("/transactions/{id}", id))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.external_id").value(payload.get("external_id").asText()))
                        .andExpect(jsonPath("$.currency").value(payload.get("currency").asText()))
                        .andExpect(jsonPath("$.total").value(payload.get("total").decimalValue().doubleValue()))
                        .andExpect(jsonPath("$.metadata").exists())
                        .andExpect(jsonPath("$.tax_lines", hasSize(payload.get("tax_lines").size())))
                        .andExpect(jsonPath("$.fee_lines", hasSize(payload.get("fee_lines").size())));
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
}
