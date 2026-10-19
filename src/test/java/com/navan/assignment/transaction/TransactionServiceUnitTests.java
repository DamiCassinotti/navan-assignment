package com.navan.assignment.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navan.assignment.common.errors.BadRequestException;
import com.navan.assignment.transaction.model.FeeLine;
import com.navan.assignment.transaction.model.TaxLine;
import com.navan.assignment.transaction.model.Transaction;
import com.navan.assignment.transaction.model.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class TransactionServiceUnitTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private TransactionRepository transactionRepository;
    private TransactionService service;

    @BeforeEach
    void setup() {
        transactionRepository = mock(TransactionRepository.class);
        service = new TransactionService(transactionRepository, new MetadataValidator());
    }

    @Nested
    class CreateTransaction {

        @Test
        void insertsAndReturnsCreated() throws Exception {
            JsonNode metadata = mapper.readTree("{\"origin\":\"TXL\",\"destination\":\"LHR\"}");
            CreateTransactionResult result = service.create(
                    "ext-1", TransactionType.FLIGHT, "EUR",
                    Instant.parse("2026-03-11T06:40:00Z"),
                    new BigDecimal("220.00"), metadata,
                    List.of(new TaxLine("Airport tax", null, new BigDecimal("15.00"))),
                    List.of(new FeeLine("YQ", new BigDecimal("25.00")))
            );
            assertTrue(result.created());
            assertEquals("ext-1", result.transaction().externalId());
            verify(transactionRepository).insertHeader(any(Transaction.class));
            verify(transactionRepository).insertLines(any(Transaction.class));
        }

        @Test
        void rejectsBadMetadata() throws Exception {
            JsonNode metadata = mapper.readTree("{\"fee_kind\":\"nope\"}");
            assertThrows(BadRequestException.class, () ->
                    service.create("ext-x", TransactionType.NAVAN_FEE, "EUR",
                            Instant.now(), new BigDecimal("10"), metadata, List.of(), List.of()));
            verifyNoInteractions(transactionRepository);
        }
    }

    @Nested
    class DuplicateHandling {

        @Test
        void returnsExistingOnUniqueViolation() throws Exception {
            JsonNode metadata = mapper.readTree("{\"origin\":\"TXL\",\"destination\":\"LHR\"}");
            doThrow(new DataIntegrityViolationException("dup")).when(transactionRepository).insertHeader(any());
            Transaction existing = new Transaction(
                    UUID.randomUUID(), "ext-1", TransactionType.FLIGHT, "EUR",
                    Instant.parse("2026-03-11T06:40:00Z"), new BigDecimal("220.00"),
                    metadata, List.of(), List.of(), Instant.now(), Instant.now());
            when(transactionRepository.findByExternalId("ext-1")).thenReturn(Optional.of(existing));

            CreateTransactionResult result = service.create("ext-1", TransactionType.FLIGHT, "EUR",
                    Instant.parse("2026-03-11T06:40:00Z"), new BigDecimal("220.00"),
                    metadata, List.of(), List.of());

            assertFalse(result.created());
            assertEquals(existing.id(), result.transaction().id());
            verify(transactionRepository, never()).insertLines(any());
        }

        @Test
        void rethrowsWhenRowCannotBeFoundAfterViolation() throws Exception {
            JsonNode metadata = mapper.readTree("{\"origin\":\"TXL\",\"destination\":\"LHR\"}");
            doThrow(new DataIntegrityViolationException("dup")).when(transactionRepository).insertHeader(any());
            when(transactionRepository.findByExternalId(any())).thenReturn(Optional.empty());

            assertThrows(DataIntegrityViolationException.class, () ->
                    service.create("ext-missing", TransactionType.FLIGHT, "EUR",
                            Instant.now(), new BigDecimal("1"), metadata, List.of(), List.of()));
        }
    }
}
