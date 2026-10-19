package com.navan.assignment.invoice;

import com.navan.assignment.common.errors.BadRequestException;
import com.navan.assignment.common.errors.ConflictException;
import com.navan.assignment.common.errors.NotFoundException;
import com.navan.assignment.invoice.model.Invoice;
import com.navan.assignment.invoice.model.InvoiceFeeLine;
import com.navan.assignment.invoice.model.InvoiceLine;
import com.navan.assignment.invoice.model.InvoiceTaxLine;
import com.navan.assignment.invoice.model.RollupEntry;
import com.navan.assignment.transaction.TransactionRepository;
import com.navan.assignment.transaction.model.FeeLine;
import com.navan.assignment.transaction.model.TaxLine;
import com.navan.assignment.transaction.model.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvoiceService {

    private final TransactionRepository transactionRepository;
    private final InvoiceRepository invoiceRepository;

    public InvoiceService(TransactionRepository transactionRepository, InvoiceRepository invoiceRepository) {
        this.transactionRepository = transactionRepository;
        this.invoiceRepository = invoiceRepository;
    }

    @Transactional
    public Invoice create(List<UUID> requestedIds) {
        UUID id = UUID.randomUUID();
        Invoice invoice = buildSnapshot(id, requestedIds, Instant.now());
        invoiceRepository.insert(invoice);
        return invoice;
    }

    @Transactional
    public Invoice update(UUID id, List<UUID> requestedIds) {
        Invoice existing = invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("invoice not found: " + id));
        Invoice rebuilt = buildSnapshot(id, requestedIds, existing.createdAt());
        invoiceRepository.deleteSnapshotRows(id);
        invoiceRepository.updateHeader(rebuilt);
        invoiceRepository.insertLines(rebuilt);
        return rebuilt;
    }

    @Transactional
    public void delete(UUID id) {
        int deleted = invoiceRepository.deleteById(id);
        if (deleted == 0) {
            throw new NotFoundException("invoice not found: " + id);
        }
    }

    private Invoice buildSnapshot(UUID invoiceId, List<UUID> requestedIds, Instant createdAt) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            throw new BadRequestException("at least one transaction_id required");
        }

        Set<UUID> distinctIds = new LinkedHashSet<>(requestedIds);

        List<Transaction> fetched = transactionRepository.findAllByIds(distinctIds);
        if (fetched.size() < distinctIds.size()) {
            Set<UUID> found = fetched.stream().map(Transaction::id).collect(Collectors.toSet());
            UUID missing = distinctIds.stream().filter(id -> !found.contains(id)).findFirst().orElseThrow();
            throw new NotFoundException("transaction not found: " + missing);
        }

        Map<UUID, Transaction> byId = fetched.stream()
                .collect(Collectors.toMap(Transaction::id, t -> t));
        List<Transaction> transactions = new ArrayList<>(distinctIds.size());
        for (UUID id : distinctIds) {
            transactions.add(byId.get(id));
        }

        String currency = transactions.getFirst().currency();
        for (Transaction tx : transactions) {
            if (!currency.equals(tx.currency())) {
                throw new ConflictException(
                        "mixed currencies on invoice: " + currency + " vs " + tx.currency());
            }
        }

        List<InvoiceLine> lines = new ArrayList<>(transactions.size());
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (Transaction tx : transactions) {
            List<InvoiceTaxLine> taxes = tx.taxLines().stream()
                    .map(t -> new InvoiceTaxLine(t.name(), t.rate(), t.amount()))
                    .toList();
            List<InvoiceFeeLine> fees = tx.feeLines().stream()
                    .map(f -> new InvoiceFeeLine(f.name(), f.amount()))
                    .toList();
            lines.add(new InvoiceLine(
                    UUID.randomUUID(),
                    tx.id(),
                    tx.type(),
                    tx.currency(),
                    tx.occurredAt(),
                    tx.total(),
                    tx.metadata(),
                    taxes,
                    fees
            ));
            grandTotal = grandTotal.add(tx.total());
        }

        return new Invoice(
                invoiceId,
                currency,
                grandTotal,
                buildTaxRollup(transactions),
                buildFeeRollup(transactions),
                lines,
                createdAt
        );
    }

    public InvoicePage findPage(int limit, int offset) {
        if (limit < 1) throw new BadRequestException("limit must be >= 1");
        if (limit > 100) throw new BadRequestException("limit must be <= 100");
        if (offset < 0) throw new BadRequestException("offset must be >= 0");
        List<Invoice> rows = invoiceRepository.findAll(limit + 1, offset);
        boolean hasMore = rows.size() > limit;
        List<Invoice> items = hasMore ? rows.subList(0, limit) : rows;
        return new InvoicePage(items, limit, offset, hasMore);
    }

    public Invoice findById(UUID id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("invoice not found: " + id));
    }

    private static List<RollupEntry> buildTaxRollup(List<Transaction> transactions) {
        Map<Map.Entry<String, BigDecimal>, BigDecimal> acc = new LinkedHashMap<>();
        for (Transaction tx : transactions) {
            for (TaxLine line : tx.taxLines()) {
                Map.Entry<String, BigDecimal> key = new AbstractMap.SimpleEntry<>(line.name(), line.rate());
                acc.merge(key, line.amount(), BigDecimal::add);
            }
        }
        List<RollupEntry> result = new ArrayList<>(acc.size());
        for (Map.Entry<Map.Entry<String, BigDecimal>, BigDecimal> entry : acc.entrySet()) {
            result.add(new RollupEntry(entry.getKey().getKey(), entry.getKey().getValue(), entry.getValue()));
        }
        return result;
    }

    private static List<RollupEntry> buildFeeRollup(List<Transaction> transactions) {
        Map<String, BigDecimal> acc = new LinkedHashMap<>();
        for (Transaction tx : transactions) {
            for (FeeLine line : tx.feeLines()) {
                acc.merge(line.name(), line.amount(), BigDecimal::add);
            }
        }
        List<RollupEntry> result = new ArrayList<>(acc.size());
        for (Map.Entry<String, BigDecimal> entry : acc.entrySet()) {
            result.add(new RollupEntry(entry.getKey(), null, entry.getValue()));
        }
        return result;
    }

}
