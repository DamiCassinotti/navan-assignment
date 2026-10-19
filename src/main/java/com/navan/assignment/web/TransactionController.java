package com.navan.assignment.web;

import com.navan.assignment.common.errors.BadRequestException;
import com.navan.assignment.transaction.CreateTransactionResult;
import com.navan.assignment.transaction.TransactionPage;
import com.navan.assignment.transaction.TransactionService;
import com.navan.assignment.transaction.model.FeeLine;
import com.navan.assignment.transaction.model.TaxLine;
import com.navan.assignment.web.dto.CreateTransactionRequest;
import com.navan.assignment.web.dto.FeeLineDto;
import com.navan.assignment.web.dto.TaxLineDto;
import com.navan.assignment.web.dto.TransactionPageResponse;
import com.navan.assignment.web.dto.TransactionResponse;
import com.navan.assignment.web.dto.UpdateTransactionRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(@RequestBody CreateTransactionRequest request) {
        requireFields(request);
        CreateTransactionResult result = transactionService.create(
                request.externalId(),
                request.type(),
                request.currency(),
                request.occurredAt(),
                request.total(),
                request.metadata(),
                mapTaxes(request.taxLines()),
                mapFees(request.feeLines())
        );
        TransactionResponse body = TransactionResponse.from(result.transaction());
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(body);
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable UUID id) {
        return TransactionResponse.from(transactionService.findById(id));
    }

    @PatchMapping("/{id}")
    public TransactionResponse update(@PathVariable UUID id, @RequestBody UpdateTransactionRequest request) {
        requireUpdateFields(request);
        return TransactionResponse.from(transactionService.update(
                id,
                request.type(),
                request.currency(),
                request.occurredAt(),
                request.total(),
                request.metadata(),
                mapTaxes(request.taxLines()),
                mapFees(request.feeLines())
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        transactionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public TransactionPageResponse list(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        TransactionPage page = transactionService.findPage(limit, offset);
        List<TransactionResponse> items = page.items().stream()
                .map(TransactionResponse::from)
                .toList();
        return new TransactionPageResponse(items, page.limit(), page.offset(), page.hasMore());
    }

    private static void requireFields(CreateTransactionRequest request) {
        if (request == null) throw new BadRequestException("body required");
        if (!StringUtils.hasText(request.externalId()))
            throw new BadRequestException("external_id required");
        if (request.type() == null) throw new BadRequestException("type required");
        if (!StringUtils.hasText(request.currency()))
            throw new BadRequestException("currency required");
        if (request.occurredAt() == null) throw new BadRequestException("occurred_at required");
        if (request.total() == null) throw new BadRequestException("total required");
    }

    private static void requireUpdateFields(UpdateTransactionRequest request) {
        if (request == null) throw new BadRequestException("body required");
        if (request.type() == null) throw new BadRequestException("type required");
        if (!StringUtils.hasText(request.currency()))
            throw new BadRequestException("currency required");
        if (request.occurredAt() == null) throw new BadRequestException("occurred_at required");
        if (request.total() == null) throw new BadRequestException("total required");
    }

    private static List<TaxLine> mapTaxes(List<TaxLineDto> taxLines) {
        if (taxLines == null) return List.of();
        List<TaxLine> result = new java.util.ArrayList<>(taxLines.size());
        for (int i = 0; i < taxLines.size(); i++) {
            TaxLineDto tax = taxLines.get(i);
            if (tax == null) throw new BadRequestException("tax_lines[" + i + "] must be an object");
            if (!StringUtils.hasText(tax.name()))
                throw new BadRequestException("tax_lines[" + i + "].name is required");
            if (tax.amount() == null)
                throw new BadRequestException("tax_lines[" + i + "].amount is required");
            result.add(new TaxLine(tax.name(), tax.rate(), tax.amount()));
        }
        return result;
    }

    private static List<FeeLine> mapFees(List<FeeLineDto> feeLines) {
        if (feeLines == null) return List.of();
        List<FeeLine> result = new java.util.ArrayList<>(feeLines.size());
        for (int i = 0; i < feeLines.size(); i++) {
            FeeLineDto fee = feeLines.get(i);
            if (fee == null) throw new BadRequestException("fee_lines[" + i + "] must be an object");
            if (!StringUtils.hasText(fee.name()))
                throw new BadRequestException("fee_lines[" + i + "].name is required");
            if (fee.amount() == null)
                throw new BadRequestException("fee_lines[" + i + "].amount is required");
            result.add(new FeeLine(fee.name(), fee.amount()));
        }
        return result;
    }
}
