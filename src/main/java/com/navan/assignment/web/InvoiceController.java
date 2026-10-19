package com.navan.assignment.web;

import com.navan.assignment.common.errors.BadRequestException;
import com.navan.assignment.invoice.InvoicePage;
import com.navan.assignment.invoice.InvoiceService;
import com.navan.assignment.invoice.model.Invoice;
import com.navan.assignment.web.dto.CreateInvoiceRequest;
import com.navan.assignment.web.dto.InvoicePageResponse;
import com.navan.assignment.web.dto.InvoiceResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @PostMapping
    public ResponseEntity<InvoiceResponse> create(@RequestBody CreateInvoiceRequest request) {
        if (request == null) throw new BadRequestException("body required");
        List<UUID> ids = request.ids();
        if (ids.isEmpty()) {
            throw new BadRequestException("transaction_id or transaction_ids required");
        }
        Invoice invoice = invoiceService.create(ids);
        return ResponseEntity.status(HttpStatus.CREATED).body(InvoiceResponse.from(invoice));
    }

    @GetMapping("/{id}")
    public InvoiceResponse get(@PathVariable UUID id) {
        return InvoiceResponse.from(invoiceService.findById(id));
    }

    @PatchMapping("/{id}")
    public InvoiceResponse update(@PathVariable UUID id, @RequestBody CreateInvoiceRequest request) {
        if (request == null) throw new BadRequestException("body required");
        List<UUID> ids = request.ids();
        if (ids.isEmpty()) {
            throw new BadRequestException("transaction_id or transaction_ids required");
        }
        return InvoiceResponse.from(invoiceService.update(id, ids));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        invoiceService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public InvoicePageResponse list(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        InvoicePage page = invoiceService.findPage(limit, offset);
        List<InvoiceResponse> items = page.items().stream()
                .map(InvoiceResponse::from)
                .toList();
        return new InvoicePageResponse(items, page.limit(), page.offset(), page.hasMore());
    }
}
