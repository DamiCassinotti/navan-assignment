package com.navan.assignment.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navan.assignment.common.errors.BadRequestException;
import com.navan.assignment.common.errors.NotFoundException;
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

    @Nested
    class UpdateTransaction {

        @Test
        void atomicallyReplacesHeaderAndLines() throws Exception {
            UUID id = UUID.randomUUID();
            JsonNode originalMetadata = mapper.readTree("{\"origin\":\"TXL\",\"destination\":\"LHR\"}");
            Instant createdAt = Instant.parse("2026-03-11T06:40:00Z");
            Transaction existing = new Transaction(
                    id, "ext-1", TransactionType.FLIGHT, "EUR",
                    createdAt, new BigDecimal("220.00"), originalMetadata,
                    List.of(new TaxLine("old-tax", null, new BigDecimal("5.00"))),
                    List.of(new FeeLine("old-fee", new BigDecimal("2.00"))),
                    createdAt, createdAt);
            when(transactionRepository.findById(id)).thenReturn(Optional.of(existing));

            JsonNode newMetadata = mapper.readTree("{\"origin\":\"TXL\",\"destination\":\"CDG\"}");
            Transaction result = service.update(
                    id, TransactionType.FLIGHT, "USD",
                    Instant.parse("2026-04-01T10:00:00Z"),
                    new BigDecimal("300.00"), newMetadata,
                    List.of(new TaxLine("new-tax", null, new BigDecimal("10.00"))),
                    List.of(new FeeLine("new-fee", new BigDecimal("3.00"))));

            assertEquals(id, result.id());
            assertEquals("ext-1", result.externalId());
            assertEquals("USD", result.currency());
            assertEquals(new BigDecimal("300.00"), result.total());
            assertEquals(1, result.taxLines().size());
            assertEquals("new-tax", result.taxLines().get(0).name());

            var inOrder = inOrder(transactionRepository);
            inOrder.verify(transactionRepository).findById(id);
            inOrder.verify(transactionRepository).updateHeader(any(Transaction.class));
            inOrder.verify(transactionRepository).deleteLines(id);
            inOrder.verify(transactionRepository).insertLines(any(Transaction.class));
        }

        @Test
        void throwsNotFoundWhenMissing() throws Exception {
            UUID id = UUID.randomUUID();
            when(transactionRepository.findById(id)).thenReturn(Optional.empty());
            JsonNode metadata = mapper.readTree("{\"origin\":\"TXL\",\"destination\":\"LHR\"}");

            assertThrows(NotFoundException.class, () ->
                    service.update(id, TransactionType.FLIGHT, "EUR",
                            Instant.now(), new BigDecimal("1"), metadata, List.of(), List.of()));
            verify(transactionRepository, never()).updateHeader(any());
            verify(transactionRepository, never()).deleteLines(any());
            verify(transactionRepository, never()).insertLines(any());
        }

        @Test
        void rejectsBadMetadataBeforeTouchingRepo() throws Exception {
            JsonNode metadata = mapper.readTree("{\"fee_kind\":\"nope\"}");
            assertThrows(BadRequestException.class, () ->
                    service.update(UUID.randomUUID(), TransactionType.NAVAN_FEE, "EUR",
                            Instant.now(), new BigDecimal("10"), metadata, List.of(), List.of()));
            verifyNoInteractions(transactionRepository);
        }
    }

    @Nested
    class DeleteTransaction {

        @Test
        void deletesExistingRow() {
            UUID id = UUID.randomUUID();
            when(transactionRepository.deleteById(id)).thenReturn(1);
            service.delete(id);
            verify(transactionRepository).deleteById(id);
        }

        @Test
        void throwsNotFoundWhenNothingDeleted() {
            UUID id = UUID.randomUUID();
            when(transactionRepository.deleteById(id)).thenReturn(0);
            assertThrows(NotFoundException.class, () -> service.delete(id));
        }
    }
}
