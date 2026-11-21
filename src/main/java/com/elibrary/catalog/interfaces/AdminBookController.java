package com.elibrary.catalog.interfaces;

import com.elibrary.catalog.application.CatalogService;
import com.elibrary.catalog.domain.BookFormat;
import com.elibrary.catalog.domain.BookMetadata;
import com.elibrary.catalog.domain.BookStatus;
import com.elibrary.shared.web.api.Resp;
import com.elibrary.shared.web.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供管理员书目与借阅席位维护 API。所有端点都会在进入应用服务前验证 ADMIN 角色。
 */
@RestController
@Validated
@RequestMapping("/api/v1/admin/books")
@RequiredArgsConstructor
public class AdminBookController {
    private final CatalogService service;

    /**
     * 建立 DRAFT 书目，并在同一事务内初始化零容量借阅库存。
     *
     * @param request 经过字段验证的书目创建数据
     * @return 新建书目的统一响应
     */
    @PostMapping
    public Resp<BookDtos.BookResponse> create(@Valid @RequestBody BookDtos.BookCreateRequest request) {
        UserContext.requireAdmin();
        return Resp.success(service.create(toMetadata(request)));
    }

    /**
     * 以请求中的 version 执行乐观锁更新，避免管理员覆盖其他人的修改。
     *
     * @param bookId  待更新书籍标识码，必须为正整数
     * @param request 经过字段验证的完整书目数据及预期版本
     * @return 更新后书目的统一响应
     */
    @PutMapping("/{bookId}")
    public Resp<BookDtos.BookResponse> update(@PathVariable @Positive long bookId,
                                              @Valid @RequestBody BookDtos.BookUpdateRequest request) {
        UserContext.requireAdmin();
        return Resp.success(service.update(bookId, request.getVersion(), toMetadata(request)));
    }

    /**
     * 将书籍切换为可供普通用户浏览与借阅的 PUBLISHED 状态。
     *
     * @param bookId 待上架书籍标识码，必须为正整数
     * @return 上架后书目的统一响应
     */
    @PostMapping("/{bookId}/publish")
    public Resp<BookDtos.BookResponse> publish(@PathVariable @Positive long bookId) {
        UserContext.requireAdmin();
        return Resp.success(service.changeStatus(bookId, BookStatus.PUBLISHED));
    }

    /**
     * 将书籍下架；已有 Loan 仍可正常查询与归还。
     *
     * @param bookId 待下架书籍标识码，必须为正整数
     * @return 下架后书目的统一响应
     */
    @PostMapping("/{bookId}/offline")
    public Resp<BookDtos.BookResponse> offline(@PathVariable @Positive long bookId) {
        UserContext.requireAdmin();
        return Resp.success(service.changeStatus(bookId, BookStatus.OFFLINE));
    }

    /**
     * 调整总借阅席位；新总量不得低于当前已借出的数量。
     *
     * @param bookId 待调整书籍标识码，必须为正整数
     * @param request 经过字段验证的新席位总量
     * @return 调整后书目与可借数量的统一响应
     */
    @PutMapping("/{bookId}/stock")
    public Resp<BookDtos.BookResponse> resizeStock(@PathVariable @Positive long bookId,
                                                   @Valid @RequestBody BookDtos.StockRequest request) {
        UserContext.requireAdmin();
        return Resp.success(service.resizeStock(bookId, request.getTotalCopies()));
    }

    /**
     * 将 HTTP 请求转换为不依赖传输格式的领域值对象。
     *
     * @param request 书目创建或更新请求
     * @return 经过领域构造规则整理的书目数据
     */
    private static BookMetadata toMetadata(BookDtos.BookMetadataRequest request) {
        return new BookMetadata(request.getIsbn(), request.getTitle(), request.getSubtitle(),
                request.getAuthors(), request.getPublisher(), request.getCategoryId(),
                request.getLanguage(), request.getDescription(), request.getCoverUrl(),
                request.getPublishedDate(), BookFormat.from(request.getFormat()));
    }
}
