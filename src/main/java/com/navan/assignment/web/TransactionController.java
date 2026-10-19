package com.navan.assignment.web;

import com.navan.assignment.common.errors.BadRequestException;
import com.navan.assignment.common.errors.NotFoundException;
import com.navan.assignment.transaction.CreateTransactionResult;
import com.navan.assignment.transaction.TransactionRepository;
import com.navan.assignment.transaction.TransactionService;
import com.navan.assignment.transaction.model.FeeLine;
import com.navan.assignment.transaction.model.TaxLine;
import com.navan.assignment.web.dto.CreateTransactionRequest;
import com.navan.assignment.web.dto.FeeLineDto;
import com.navan.assignment.web.dto.TaxLineDto;
import com.navan.assignment.web.dto.TransactionResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService service;
    private final TransactionRepository repository;

    public TransactionController(TransactionService service,
                                 TransactionRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(@RequestBody CreateTransactionRequest request) {
        requireFields(request);
        CreateTransactionResult result = service.create(
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
        return repository.findById(id)
                .map(TransactionResponse::from)
                .orElseThrow(() -> new NotFoundException("transaction not found: " + id));
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

    private static List<TaxLine> mapTaxes(List<TaxLineDto> taxLines) {
        if (taxLines == null) return List.of();
        return taxLines.stream()
                .map(tax -> new TaxLine(tax.name(), tax.rate(), tax.amount()))
                .toList();
    }

    private static List<FeeLine> mapFees(List<FeeLineDto> feeLines) {
        if (feeLines == null) return List.of();
        return feeLines.stream()
                .map(fee -> new FeeLine(fee.name(), fee.amount()))
                .toList();
    }
}
