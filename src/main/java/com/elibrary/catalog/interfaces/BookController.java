package com.elibrary.catalog.interfaces;

import com.elibrary.catalog.application.CatalogService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/books")
public class BookController {
    private final CatalogService service;

    public BookController(CatalogService service) {
        this.service = service;
    }

    @GetMapping
    public BookDtos.BookPage browse(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) @Size(max = 10) String language,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        Long category = categoryId == null ? null : Long.parseUnsignedLong(categoryId);
        return service.browse(q, category, language, format, cursor, size);
    }

    @GetMapping("/{bookId}")
    public ResponseEntity<BookDtos.BookResponse> get(@PathVariable String bookId) {
        var response = service.get(Long.parseUnsignedLong(bookId), false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }
}
