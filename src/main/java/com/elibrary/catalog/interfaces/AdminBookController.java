package com.elibrary.catalog.interfaces;

import com.elibrary.catalog.application.CatalogService;
import com.elibrary.shared.web.UserContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/books")
public class AdminBookController {
    private final CatalogService service;

    public AdminBookController(CatalogService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookDtos.BookResponse create(@Valid @RequestBody BookDtos.BookUpsertRequest request) {
        UserContext.requireAdmin();
        return service.create(request);
    }

    @PutMapping("/{bookId}")
    public BookDtos.BookResponse update(@PathVariable String bookId,
                                        @Valid @RequestBody BookDtos.BookUpsertRequest request) {
        UserContext.requireAdmin();
        return service.update(Long.parseUnsignedLong(bookId), request);
    }

    @PostMapping("/{bookId}/publish")
    public BookDtos.BookResponse publish(@PathVariable String bookId) {
        UserContext.requireAdmin();
        return service.changeStatus(Long.parseUnsignedLong(bookId), "PUBLISHED");
    }

    @PostMapping("/{bookId}/offline")
    public BookDtos.BookResponse offline(@PathVariable String bookId) {
        UserContext.requireAdmin();
        return service.changeStatus(Long.parseUnsignedLong(bookId), "OFFLINE");
    }

    @PutMapping("/{bookId}/stock")
    public BookDtos.BookResponse resizeStock(@PathVariable String bookId,
                                             @Valid @RequestBody BookDtos.StockRequest request) {
        UserContext.requireAdmin();
        return service.resizeStock(Long.parseUnsignedLong(bookId), request.totalCopies());
    }
}
