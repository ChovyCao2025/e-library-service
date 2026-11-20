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

/**
 * 提供公開書目查詢 API。一般使用者只能瀏覽已上架書籍，
 * 動態可借數量會與書目中繼資料一起回傳。
 */
@Validated
@RestController
@RequestMapping("/api/v1/books")
public class BookController {
    private final CatalogService service;

    public BookController(CatalogService service) {
        this.service = service;
    }

    /**
     * 依書名、分類、語言及格式篩選書目，並使用與查詢條件綁定的簽章游標分頁。
     */
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

    /**
     * 查詢單本已上架書籍。回應包含動態庫存，因此停用 HTTP 快取以避免顯示過期餘量。
     */
    @GetMapping("/{bookId}")
    public ResponseEntity<BookDtos.BookResponse> get(@PathVariable String bookId) {
        var response = service.get(Long.parseUnsignedLong(bookId), false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }
}
