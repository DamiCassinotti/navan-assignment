package com.navan.assignment.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navan.assignment.common.errors.ConflictException;
import com.navan.assignment.common.errors.NotFoundException;
import com.navan.assignment.invoice.model.Invoice;
import com.navan.assignment.transaction.TransactionRepository;
import com.navan.assignment.transaction.model.FeeLine;
import com.navan.assignment.transaction.model.TaxLine;
import com.navan.assignment.transaction.model.Transaction;
import com.navan.assignment.transaction.model.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceUnitTests {

    @Mock TransactionRepository transactionRepository;
    @Mock InvoiceRepository invoiceRepository;
    @InjectMocks InvoiceService invoiceService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Transaction tx(UUID id, String currency, BigDecimal total, List<TaxLine> taxes, List<FeeLine> fees) {
        ObjectNode meta = objectMapper.createObjectNode().put("k", "v");
        Instant now = Instant.parse("2026-03-11T06:40:00Z");
        return new Transaction(id, "ext-" + id, TransactionType.FLIGHT, currency, now, total, meta,
                taxes, fees, now, now);
    }

    @BeforeEach
    void stubbed() {}

    @Test
    void dedupesRepeatedIdsAndRollsUpByNameAndRate() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Transaction txA = tx(a, "EUR", new BigDecimal("220.00"),
                List.of(new TaxLine("Airport tax", null, new BigDecimal("15.00"))),
                List.of(new FeeLine("YQ", new BigDecimal("25.00"))));
        Transaction txB = tx(b, "EUR", new BigDecimal("331.70"),
                List.of(new TaxLine("VAT", new BigDecimal("0.07"), new BigDecimal("21.70"))),
                List.of());
        when(transactionRepository.findAllByIds(anyCollection())).thenReturn(List.of(txA, txB));

        Invoice invoice = invoiceService.create(List.of(a, a, b, a));

        assertThat(invoice.lines()).hasSize(2);
        assertThat(invoice.grandTotal()).isEqualByComparingTo("551.70");
        assertThat(invoice.taxRollup()).hasSize(2);
        assertThat(invoice.feeRollup()).hasSize(1);
        verify(invoiceRepository).insert(any(Invoice.class));
    }

    @Test
    void mixedCurrencyThrowsConflictAndDoesNotPersist() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Transaction txA = tx(a, "EUR", new BigDecimal("10.00"), List.of(), List.of());
        Transaction txB = tx(b, "USD", new BigDecimal("10.00"), List.of(), List.of());
        when(transactionRepository.findAllByIds(anyCollection())).thenReturn(List.of(txA, txB));

        assertThatThrownBy(() -> invoiceService.create(List.of(a, b)))
                .isInstanceOf(ConflictException.class);
        verify(invoiceRepository, never()).insert(any());
    }

    @Test
    void missingTransactionThrows404() {
        UUID a = UUID.randomUUID();
        when(transactionRepository.findAllByIds(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> invoiceService.create(List.of(a)))
                .isInstanceOf(NotFoundException.class);
        verify(invoiceRepository, never()).insert(any());
    }

    @Test
    void taxRollupMergesRatesWithDifferentScales() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Transaction txA = tx(a, "EUR", new BigDecimal("100.00"),
                List.of(new TaxLine("VAT", new BigDecimal("0.07"), new BigDecimal("7.00"))),
                List.of());
        Transaction txB = tx(b, "EUR", new BigDecimal("100.00"),
                List.of(new TaxLine("VAT", new BigDecimal("0.070"), new BigDecimal("7.00"))),
                List.of());
        when(transactionRepository.findAllByIds(anyCollection())).thenReturn(List.of(txA, txB));

        Invoice invoice = invoiceService.create(List.of(a, b));

        assertThat(invoice.taxRollup()).hasSize(1);
        assertThat(invoice.taxRollup().get(0).name()).isEqualTo("VAT");
        assertThat(invoice.taxRollup().get(0).amount()).isEqualByComparingTo("14.00");
    }

    @Test
    void feeRollupSumsSameNameAcrossTransactions() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Transaction txA = tx(a, "EUR", new BigDecimal("10.00"), List.of(),
                List.of(new FeeLine("Booking fee", new BigDecimal("4.00"))));
        Transaction txB = tx(b, "EUR", new BigDecimal("10.00"), List.of(),
                List.of(new FeeLine("Booking fee", new BigDecimal("6.00"))));
        when(transactionRepository.findAllByIds(anyCollection())).thenReturn(List.of(txA, txB));

        Invoice invoice = invoiceService.create(List.of(a, b));

        assertThat(invoice.feeRollup()).hasSize(1);
        assertThat(invoice.feeRollup().get(0).name()).isEqualTo("Booking fee");
        assertThat(invoice.feeRollup().get(0).amount()).isEqualByComparingTo("10.00");
    }
}
