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

/**
 * 提供管理員書目與借閱席位維護 API。所有端點都會在進入應用服務前驗證 ADMIN 角色。
 */
@RestController
@RequestMapping("/api/v1/admin/books")
public class AdminBookController {
    private final CatalogService service;

    public AdminBookController(CatalogService service) {
        this.service = service;
    }

    /** 建立 DRAFT 書目，並在同一交易內初始化零庫存與書籍投影控制資料。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookDtos.BookResponse create(@Valid @RequestBody BookDtos.BookUpsertRequest request) {
        UserContext.requireAdmin();
        return service.create(request);
    }

    /** 以請求中的 version 執行樂觀鎖更新，避免管理員覆蓋其他人的修改。 */
    @PutMapping("/{bookId}")
    public BookDtos.BookResponse update(@PathVariable String bookId,
                                        @Valid @RequestBody BookDtos.BookUpsertRequest request) {
        UserContext.requireAdmin();
        return service.update(Long.parseUnsignedLong(bookId), request);
    }

    /** 將書籍切換為可被一般使用者瀏覽與借閱的 PUBLISHED 狀態。 */
    @PostMapping("/{bookId}/publish")
    public BookDtos.BookResponse publish(@PathVariable String bookId) {
        UserContext.requireAdmin();
        return service.changeStatus(Long.parseUnsignedLong(bookId), "PUBLISHED");
    }

    /** 將書籍下架；既有 Loan 仍可正常查詢與歸還。 */
    @PostMapping("/{bookId}/offline")
    public BookDtos.BookResponse offline(@PathVariable String bookId) {
        UserContext.requireAdmin();
        return service.changeStatus(Long.parseUnsignedLong(bookId), "OFFLINE");
    }

    /** 調整總借閱席位；新總量不得低於目前已借出的數量。 */
    @PutMapping("/{bookId}/stock")
    public BookDtos.BookResponse resizeStock(@PathVariable String bookId,
                                             @Valid @RequestBody BookDtos.StockRequest request) {
        UserContext.requireAdmin();
        return service.resizeStock(Long.parseUnsignedLong(bookId), request.totalCopies());
    }
}
