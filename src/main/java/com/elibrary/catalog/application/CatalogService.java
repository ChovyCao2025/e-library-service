package com.elibrary.catalog.application;

import com.elibrary.catalog.domain.BookFormat;
import com.elibrary.catalog.domain.BookMetadata;
import com.elibrary.catalog.domain.BookStatus;
import com.elibrary.catalog.infrastructure.BookRow;
import com.elibrary.catalog.infrastructure.CatalogMapper;
import com.elibrary.catalog.interfaces.BookDtos;
import com.elibrary.lending.application.LendingInventoryService;
import com.elibrary.lending.application.LendingStockReader;
import com.elibrary.shared.kernel.ClockProvider;
import com.elibrary.shared.kernel.SnowflakeIdGenerator;
import com.elibrary.shared.outbox.application.OutboxWriter;
import com.elibrary.shared.outbox.domain.AggregateType;
import com.elibrary.shared.outbox.domain.DomainEventType;
import com.elibrary.shared.web.api.ErrorCode;
import com.elibrary.shared.web.error.DomainException;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 书目应用服务。写入方法在同一本地交易内完成书目异动与 Outbox 事件；
 * 浏览方法以最后一笔书籍识别码进行寻址分页。
 */
@Service
@RequiredArgsConstructor
public class CatalogService {
    private final CatalogMapper mapper;
    private final LendingInventoryService inventory;
    private final OutboxWriter outbox;
    private final SnowflakeIdGenerator idGenerator;
    private final ClockProvider clock;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<BookSearchReader> searchReaderProvider;
    private final LendingStockReader stockReader;

    /**
     * 依识别码取得书目。公开查询会隐藏未上架资料，内部呼叫端可明确要求包含其他状态。
     */
    @Transactional(readOnly = true)
    public BookDtos.BookResponse get(long bookId, boolean includeUnpublished) {
        // 详情查询一次带回书目与目前库存，避免组装回应时再逐项查询。
        BookRow row = mapper.findById(bookId);

        // 公开端点将未上架书目视为不存在，避免泄漏草稿或已下架内容。
        if (row == null || (!includeUnpublished && !row.getStatus().isPubliclyVisible())) {
            throw DomainException.of(ErrorCode.BOOK_NOT_FOUND);
        }

        // API 模型负责将资料库中的作者 JSON 与数值 ID 转为对外格式。
        return BookDtos.BookResponse.from(row, objectMapper, clock.now());
    }

    /**
     * 以稳定的书籍识别码进行寻址分页；第一页不传 afterBookId。
     */
    @Transactional(readOnly = true)
    public BookDtos.BookPage browse(String query, Long categoryId, String language, String format,
                                    Long afterBookId, int size) {
        // 空查询代表不限制书名；非空查询去除首尾空白后交由 Mapper 筛选。
        String normalizedQuery = query == null ? "" : query.strip();
        BookFormat normalizedFormat = BookFormat.fromNullable(format);
        long afterId = afterBookId == null ? 0L : afterBookId;

        BookSearchReader searchReader = searchReaderProvider.getIfAvailable();
        if (!normalizedQuery.isEmpty() && searchReader != null) {
            return browseSearch(searchReader, normalizedQuery, categoryId, language,
                    normalizedFormat, afterId, size);
        }

        // 多读取一笔即可判断是否仍有下一页，不需要额外执行 COUNT 查询。
        List<BookRow> rows = mapper.browse(
                normalizedQuery, categoryId, language, normalizedFormat,
                BookStatus.PUBLISHED, afterId, size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = rows.subList(0, size);
        }

        // 同一页共用相同的 asOf，让各笔库存快照具有一致的回应时间语意。
        var now = clock.now();
        var items = rows.stream().map(row -> BookDtos.BookResponse.from(row, objectMapper, now)).toList();

        // 下一页直接回传本页最后一个 bookId；最后一页不再提供位置。
        String nextBookId = hasMore
                ? Long.toUnsignedString(rows.getLast().getBookId())
                : null;
        return new BookDtos.BookPage(items, nextBookId, hasMore);
    }

    private BookDtos.BookPage browseSearch(BookSearchReader searchReader, String query,
                                           Long categoryId, String language, BookFormat format,
                                           long afterId, int size) {
        try {
            var searchQuery = new BookSearchReader.BookSearchQuery(
                    query, categoryId, language, format, afterId);
            var matches = searchReader.search(searchQuery, size + 1);
            boolean hasMore = matches.size() > size;
            if (hasMore) {
                matches = matches.subList(0, size);
            }

            var bookIds = matches.stream().map(BookSearchReader.BookSearchView::bookId).toList();
            var stocks = stockReader.findByBookIds(bookIds);
            var now = clock.now();
            var items = matches.stream().map(book -> {
                var stock = stocks.get(book.bookId());
                BookDtos.Availability availability = stock == null ? null
                        : new BookDtos.Availability(stock.availableCopies(), stock.totalCopies(), now,
                        BookDtos.AvailabilitySource.DATABASE);
                return new BookDtos.BookResponse(Long.toUnsignedString(book.bookId()), book.isbn(), book.title(),
                        book.subtitle(), book.authors(), book.publisher(),
                        Long.toUnsignedString(book.categoryId()), book.language(), book.description(),
                        book.coverUrl(), book.publishedDate(), book.format(), book.status(),
                        book.version(), availability);
            }).toList();
            String nextBookId = hasMore
                    ? Long.toUnsignedString(matches.getLast().bookId())
                    : null;
            return new BookDtos.BookPage(items, nextBookId, hasMore);
        } catch (BookSearchUnavailableException exception) {
            throw DomainException.of(ErrorCode.SEARCH_UNAVAILABLE, true, null);
        }
    }

    /** 建立草稿书目及其零容量库存，并在同一交易写入 BookCreated 事件。 */
    @Transactional(timeout = 3)
    public BookDtos.BookResponse create(BookMetadata metadata) {
        // 应用层统一使用雪花演算法产生资料库主键，API 回应时再转成十进位字串。
        long bookId = idGenerator.nextId();
        var now = nowDb();

        // 新书固定以 DRAFT、version=1 建立; ISBN 唯一性由资料库约束作最终保护。
        try {
            mapper.insertBook(bookId, metadata, BookStatus.DRAFT, now);
        } catch (DuplicateKeyException exception) {
            throw DomainException.of(ErrorCode.DUPLICATE_ISBN);
        }

        // Lending 服务使用 MANDATORY 传播，书目与零库存会在本交易中共同提交。
        inventory.initializeForNewBook(bookId);

        // 重新读取持久化结果，确保回应与事件使用资料库实际保存的版本及预设状态。
        BookRow row = mapper.findById(bookId);
        writeCatalogEvent(DomainEventType.BOOK_CREATED, row);
        return BookDtos.BookResponse.from(row, objectMapper, clock.now());
    }

    /**
     * 依 version 执行乐观锁更新；资料已被其他管理员修改时回传版本冲突。
     */
    @Transactional(timeout = 3)
    public BookDtos.BookResponse update(long bookId, Long expectedVersion, BookMetadata metadata) {
        // 更新请求必须携带已读取的版本，才能辨识并拒绝遗失更新。
        if (expectedVersion == null || expectedVersion < 1) {
            throw DomainException.of(ErrorCode.VALIDATION_FAILED, "version is required");
        }

        // SQL 在 WHERE 子句比对 version，成功时同时将资料库版本加一。
        int changed;
        try {
            changed = mapper.updateBook(bookId, expectedVersion, metadata, nowDb());
        } catch (DuplicateKeyException exception) {
            throw DomainException.of(ErrorCode.DUPLICATE_ISBN);
        }
        if (changed == 0) {
            // 影响零行可能是书目不存在，也可能是版本已被其他交易推进，分别回传稳定错误码。
            if (mapper.findById(bookId) == null) {
                throw DomainException.of(ErrorCode.BOOK_NOT_FOUND);
            }
            throw DomainException.of(ErrorCode.VERSION_CONFLICT, "Book version has changed");
        }

        // 事件与回应都使用更新后快照，事件 aggregateVersion 因此等于新的书目版本。
        BookRow row = mapper.findById(bookId);
        writeCatalogEvent(DomainEventType.BOOK_UPDATED, row);
        return BookDtos.BookResponse.from(row, objectMapper, clock.now());
    }

    /**
     * 根据当前状态执行书目生命周期迁移；状态实际改变时，将对应事件与数据更新一起提交。
     */
    @Transactional(timeout = 3)
    public BookDtos.BookResponse changeStatus(long bookId, BookStatus target) {
        // 状态本身就是迁移命令的并发条件，书目版本仍由数据库在成功迁移时递增。
        DomainEventType eventType = switch (target) {
            case PUBLISHED -> DomainEventType.BOOK_PUBLISHED;
            case OFFLINE -> DomainEventType.BOOK_OFFLINED;
            case DRAFT -> throw new IllegalArgumentException(
                    "DRAFT is not a supported status transition target");
        };

        int changed = mapper.changeStatus(
                bookId, target.allowedSourceStatuses(), target, nowDb());

        // 更新后统一读取一次快照，供返回值、幂等判断和事件使用。
        BookRow row = mapper.findById(bookId);
        if (row == null) {
            throw DomainException.of(ErrorCode.BOOK_NOT_FOUND);
        }
        if (changed == 0) {
            // 已经处于目标状态表示重复命令；其他状态表示迁移前提已不成立。
            if (row.getStatus() == target) {
                return BookDtos.BookResponse.from(row, objectMapper, clock.now());
            }
            throw DomainException.of(ErrorCode.VERSION_CONFLICT,
                    "Book status does not allow this transition");
        }

        writeCatalogEvent(eventType, row);
        return BookDtos.BookResponse.from(row, objectMapper, clock.now());
    }

    /**
     * 调整借阅总容量；总量不可低于目前借出数量。
     */
    @Transactional(timeout = 3)
    public BookDtos.BookResponse resizeStock(long bookId, int newTotal) {
        // 普通读取只用于区分书目不存在；容量不变量由 Lending 的条件更新保护。
        BookRow book = mapper.findById(bookId);
        if (book == null) {
            throw DomainException.of(ErrorCode.BOOK_NOT_FOUND);
        }

        // 库存不变量及 StockResized 事件由资料所有者 Lending 统一处理。
        inventory.resize(bookId, newTotal);

        // 调整成功后重新查询 JOIN 结果，回传最新的总容量、可借容量及库存版本。
        BookRow row = mapper.findById(bookId);
        return BookDtos.BookResponse.from(row, objectMapper, clock.now());
    }

    /** 将完整书目快照写入事件酬载，供下游以版本号更新读取模型。 */
    private void writeCatalogEvent(DomainEventType type, BookRow row) {
        // LinkedHashMap 保留固定栏位顺序，便于日志阅读及事件内容测试。
        var payload = new LinkedHashMap<String, Object>();
        payload.put("bookId", Long.toUnsignedString(row.getBookId()));
        payload.put("isbn", row.getIsbn());
        payload.put("title", row.getTitle());
        payload.put("subtitle", row.getSubtitle());
        payload.put("authors", row.getAuthors());
        payload.put("publisher", row.getPublisher());
        payload.put("categoryId", Long.toUnsignedString(row.getCategoryId()));
        payload.put("language", row.getLanguage());
        payload.put("description", row.getDescription());
        payload.put("coverUrl", row.getCoverUrl());
        payload.put("publishedDate", row.getPublishedDate());
        payload.put("format", row.getFormat());
        payload.put("status", row.getStatus());
        payload.put("version", row.getVersion());

        // OutboxWriter 参与目前交易；书目异动回滚时，事件也会一并回滚。
        outbox.append(AggregateType.BOOK, row.getBookId(), type, row.getVersion(), payload);
    }

    private LocalDateTime nowDb() {
        // MySQL DATETIME 不保存时区，专案统一先转为 UTC 后写入。
        return LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
    }
}
