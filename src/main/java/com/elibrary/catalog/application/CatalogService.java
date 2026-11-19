package com.elibrary.catalog.application;

import com.elibrary.catalog.domain.BookFormat;
import com.elibrary.catalog.infrastructure.BookRow;
import com.elibrary.catalog.infrastructure.CatalogMapper;
import com.elibrary.catalog.interfaces.BookDtos;
import com.elibrary.shared.kernel.ClockProvider;
import com.elibrary.shared.kernel.IdGenerator;
import com.elibrary.shared.kernel.RequestHasher;
import com.elibrary.shared.outbox.OutboxWriter;
import com.elibrary.shared.web.CursorCodec;
import com.elibrary.shared.web.DomainException;
import com.elibrary.shared.web.ErrorCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 書目應用服務。寫入方法在同一本地交易內完成書目異動與 Outbox 事件；
 * 瀏覽方法採用具簽章的尋址游標，避免深分頁的位移掃描成本。
 */
@Service
public class CatalogService {
    private final CatalogMapper mapper;
    private final OutboxWriter outbox;
    private final IdGenerator ids;
    private final ClockProvider clock;
    private final ObjectMapper objectMapper;
    private final CursorCodec cursors;
    private final RequestHasher hasher;

    public CatalogService(CatalogMapper mapper, OutboxWriter outbox, IdGenerator ids, ClockProvider clock,
                          ObjectMapper objectMapper, CursorCodec cursors, RequestHasher hasher) {
        this.mapper = mapper;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.cursors = cursors;
        this.hasher = hasher;
    }

    @Transactional(readOnly = true)
    public BookDtos.BookResponse get(long bookId, boolean includeUnpublished) {
        BookRow row = mapper.findById(bookId);
        if (row == null || (!includeUnpublished && !"PUBLISHED".equals(row.status()))) {
            throw DomainException.notFound(ErrorCode.BOOK_NOT_FOUND, "Book was not found");
        }
        return BookDtos.BookResponse.from(row, objectMapper, clock.now());
    }

    @Transactional(readOnly = true)
    public BookDtos.BookPage browse(String query, Long categoryId, String language, String format,
                                    String cursor, int size) {
        String normalizedQuery = query == null ? "" : query.strip();
        String fingerprint = hasher.sha256(String.join("|", normalizedQuery,
                String.valueOf(categoryId), String.valueOf(language), String.valueOf(format), String.valueOf(size)));
        var state = cursors.decode(cursor, fingerprint, size);
        long afterId = state == null ? 0 : Long.parseUnsignedLong(state.after());
        List<BookRow> rows = mapper.browse(normalizedQuery, categoryId, language, format, afterId, size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = rows.subList(0, size);
        }
        var now = clock.now();
        var items = rows.stream().map(row -> BookDtos.BookResponse.from(row, objectMapper, now)).toList();
        String next = hasMore ? cursors.encode(fingerprint, "DB", Long.toUnsignedString(rows.getLast().bookId()), size) : null;
        return new BookDtos.BookPage(items, next, hasMore);
    }

    @Transactional(timeout = 3)
    public BookDtos.BookResponse create(BookDtos.BookUpsertRequest request) {
        // 共用寫入閘門用於協調一般寫入與後續的線上搜尋索引切換。
        ensureGateOpen();
        long bookId = ids.nextId();
        var now = nowDb();
        try {
            mapper.insertBook(bookId, emptyToNull(request.isbn()), request.title(), request.subtitle(),
                    authorsJson(request.authors()), request.publisher(), parseId(request.categoryId()),
                    request.language(), request.description(), request.coverUrl(), request.publishedDate(),
                    parseFormat(request.format()), now);
        } catch (DuplicateKeyException exception) {
            throw new DomainException(ErrorCode.DUPLICATE_ISBN, HttpStatus.CONFLICT,
                    "ISBN already exists", false);
        }
        mapper.insertStock(bookId, now);
        mapper.insertBookProjection(bookId, now);
        BookRow row = mapper.findById(bookId);
        writeCatalogEvent("BookCreated", row);
        return BookDtos.BookResponse.from(row, objectMapper, clock.now());
    }

    @Transactional(timeout = 3)
    public BookDtos.BookResponse update(long bookId, BookDtos.BookUpsertRequest request) {
        ensureGateOpen();
        if (request.version() == null || request.version() < 1) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
                    "version is required", false);
        }
        int changed;
        try {
            changed = mapper.updateBook(bookId, request.version(), emptyToNull(request.isbn()), request.title(),
                    request.subtitle(), authorsJson(request.authors()), request.publisher(),
                    parseId(request.categoryId()), request.language(), request.description(), request.coverUrl(),
                    request.publishedDate(), parseFormat(request.format()), nowDb());
        } catch (DuplicateKeyException exception) {
            throw new DomainException(ErrorCode.DUPLICATE_ISBN, HttpStatus.CONFLICT,
                    "ISBN already exists", false);
        }
        if (changed == 0) {
            if (mapper.findById(bookId) == null) {
                throw DomainException.notFound(ErrorCode.BOOK_NOT_FOUND, "Book was not found");
            }
            throw DomainException.conflict(ErrorCode.VERSION_CONFLICT, "Book version has changed");
        }
        BookRow row = mapper.findById(bookId);
        writeCatalogEvent("BookUpdated", row);
        return BookDtos.BookResponse.from(row, objectMapper, clock.now());
    }

    @Transactional(timeout = 3)
    public BookDtos.BookResponse changeStatus(long bookId, String target) {
        ensureGateOpen();
        BookRow current = mapper.lockSnapshot(bookId);
        if (current == null) {
            throw DomainException.notFound(ErrorCode.BOOK_NOT_FOUND, "Book was not found");
        }
        mapper.changeStatus(bookId, target, nowDb());
        BookRow row = mapper.findById(bookId);
        writeCatalogEvent("PUBLISHED".equals(target) ? "BookPublished" : "BookOfflined", row);
        return BookDtos.BookResponse.from(row, objectMapper, clock.now());
    }

    @Transactional(timeout = 3)
    public BookDtos.BookResponse resizeStock(long bookId, int newTotal) {
        BookRow book = mapper.lockSnapshot(bookId);
        if (book == null) {
            throw DomainException.notFound(ErrorCode.BOOK_NOT_FOUND, "Book was not found");
        }
        mapper.lockBookProjection(bookId);

        // 條件更新維持 borrowed = total - available，無須掃描全部活躍借閱。
        int changed = mapper.resizeStock(bookId, newTotal, nowDb());
        if (changed == 0) {
            throw DomainException.conflict(ErrorCode.STOCK_BELOW_BORROWED,
                    "New capacity is below the number of active loans");
        }
        mapper.beginBookProjectionRebuild(bookId, nowDb());
        BookRow row = mapper.findById(bookId);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("bookId", Long.toUnsignedString(bookId));
        payload.put("totalCopies", row.totalCopies());
        payload.put("availableCopies", row.availableCopies());
        payload.put("stockVersion", row.stockVersion());
        payload.put("generation", mapper.bookProjectionGeneration(bookId));
        outbox.append("LendingStock", bookId, "StockResized", row.stockVersion(), payload, false);
        return BookDtos.BookResponse.from(row, objectMapper, clock.now());
    }

    private void ensureGateOpen() {
        if (!"OPEN".equals(mapper.lockWriteGate())) {
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "Catalog writes are temporarily paused", true);
        }
    }

    private void writeCatalogEvent(String type, BookRow row) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("bookId", Long.toUnsignedString(row.bookId()));
        payload.put("isbn", row.isbn());
        payload.put("title", row.title());
        payload.put("subtitle", row.subtitle());
        payload.put("authors", row.authors());
        payload.put("publisher", row.publisher());
        payload.put("categoryId", Long.toUnsignedString(row.categoryId()));
        payload.put("language", row.language());
        payload.put("description", row.description());
        payload.put("coverUrl", row.coverUrl());
        payload.put("publishedDate", row.publishedDate());
        payload.put("format", row.format());
        payload.put("status", row.status());
        payload.put("version", row.version());
        outbox.append("Book", row.bookId(), type, row.version(), payload, true);
    }

    private String authorsJson(List<String> authors) {
        try {
            return objectMapper.writeValueAsString(authors);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid authors", exception);
        }
    }

    private static long parseId(String value) {
        try {
            long id = Long.parseUnsignedLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("ID must be a positive integer");
        }
    }

    private static String parseFormat(String value) {
        return BookFormat.valueOf(value.toUpperCase()).name();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private LocalDateTime nowDb() {
        return LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
    }
}
