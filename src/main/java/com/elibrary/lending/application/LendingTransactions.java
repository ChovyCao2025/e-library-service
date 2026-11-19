package com.elibrary.lending.application;

import com.elibrary.catalog.infrastructure.BookRow;
import com.elibrary.catalog.infrastructure.CatalogMapper;
import com.elibrary.lending.infrastructure.BorrowRequestRow;
import com.elibrary.lending.infrastructure.LendingMapper;
import com.elibrary.lending.infrastructure.LoanRow;
import com.elibrary.lending.infrastructure.ProjectionControlRow;
import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.kernel.ClockProvider;
import com.elibrary.shared.outbox.OutboxWriter;
import com.elibrary.shared.web.DomainException;
import com.elibrary.shared.web.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;

/**
 * 承載借閱與歸還的權威 MySQL 交易。交易方法放在獨立 Spring Bean，
 * 確保每次呼叫都會經過 Spring 交易代理。
 */
@Service
public class LendingTransactions {
    private final LendingMapper mapper;
    private final CatalogMapper catalog;
    private final OutboxWriter outbox;
    private final ClockProvider clock;
    private final AppProperties.Lending properties;

    public LendingTransactions(LendingMapper mapper, CatalogMapper catalog, OutboxWriter outbox,
                               ClockProvider clock, AppProperties appProperties) {
        this.mapper = mapper;
        this.catalog = catalog;
        this.outbox = outbox;
        this.clock = clock;
        this.properties = appProperties.lending();
    }

    @Transactional(timeout = 3)
    public LoanRow finalizeBorrow(long requestId, String token) {
        var now = nowDb();

        // 固定鎖順序：請求 -> 使用者投影 -> 帳戶 -> 書目 -> 書籍投影 -> 庫存。
        BorrowRequestRow request = mapper.lockRequest(requestId);
        if (request == null || !"PROCESSING".equals(request.status()) || !token.equals(request.attemptToken())
                || !request.leaseUntil().isAfter(now)) {
            throw new DomainException(ErrorCode.REQUEST_IN_PROGRESS, HttpStatus.CONFLICT,
                    "Borrow attempt is no longer valid", true, requestId);
        }
        boolean dbOnly = "DB_ONLY".equals(request.admissionMode());
        ProjectionControlRow userProjection = lockProjection("USER", request.userId(), dbOnly);
        requireProjection(userProjection, request.quotaGeneration(), dbOnly);
        mapper.lockAccount(request.userId());
        BookRow book = catalog.lockSnapshot(request.bookId());
        if (book == null) {
            throw DomainException.notFound(ErrorCode.BOOK_NOT_FOUND, "Book was not found");
        }
        ProjectionControlRow bookProjection = lockProjection("BOOK", request.bookId(), dbOnly);
        requireProjection(bookProjection, request.stockGeneration(), dbOnly);
        mapper.lockStock(request.bookId());

        if (!"PUBLISHED".equals(book.status())) {
            throw DomainException.conflict(ErrorCode.BOOK_NOT_BORROWABLE, "Book is not published");
        }
        if (dbOnly) {
            // DB_ONLY 寫入會推進 READY 代次，使舊 Redis 命名空間失去提交資格。
            mapper.markDirtyFromReady("USER", request.userId(), now);
            mapper.markDirtyFromReady("BOOK", request.bookId(), now);
        }
        long eventQuotaGeneration = userProjection.generation()
                + (dbOnly && "READY".equals(userProjection.state()) ? 1 : 0);
        long eventStockGeneration = bookProjection.generation()
                + (dbOnly && "READY".equals(bookProjection.state()) ? 1 : 0);
        if (mapper.occupyQuota(request.userId(), properties.maxActiveLoans(), now) != 1) {
            throw DomainException.conflict(ErrorCode.LOAN_LIMIT_EXCEEDED, "Active loan limit reached");
        }
        if (mapper.occupyStock(request.bookId(), now) != 1) {
            throw DomainException.conflict(ErrorCode.BOOK_SOLD_OUT, "No lending seat is available");
        }
        try {
            // 活躍借閱唯一鍵是防止同一使用者重複借閱同一本書的最後一道併發保護。
            mapper.insertLoan(request.loanId(), request.requestId(), request.userId(), request.bookId(),
                    request.idemKey(), now, now.plusDays(properties.loanDays()), properties.policyVersion());
        } catch (DuplicateKeyException exception) {
            throw DomainException.conflict(ErrorCode.ALREADY_BORROWED, "User already has an active loan for this book");
        }
        if (mapper.markRequestSucceeded(requestId, token, dbOnly, now) != 1) {
            throw new IllegalStateException("Borrow request state changed during transaction");
        }
        LoanRow loan = mapper.findLoanByRequest(requestId);
        var payload = loanPayload(loan);
        payload.put("quotaGeneration", eventQuotaGeneration);
        payload.put("stockGeneration", eventStockGeneration);
        payload.put("accountVersion", mapper.accountVersion(request.userId()));
        payload.put("stockVersion", mapper.stockVersion(request.bookId()));

        // 業務事件與聚合資料異動在同一筆交易內寫入。
        outbox.append("Loan", loan.loanId(), "BookBorrowed", loan.version(), payload, false);
        return loan;
    }

    @Transactional(timeout = 3)
    public ReturnResult returnLoan(long userId, long loanId, String reason, boolean redisMode) {
        LoanRow preview = mapper.findLoan(loanId, userId);
        if (preview == null) {
            throw DomainException.notFound(ErrorCode.LOAN_NOT_FOUND, "Loan was not found");
        }
        var now = nowDb();

        // 歸還沿用借閱的資源鎖順序，避免借閱與歸還形成死鎖循環。
        ProjectionControlRow userProjection = lockProjection("USER", userId, !redisMode);
        requireReturnProjection(userProjection, redisMode);
        mapper.lockAccount(userId);
        BookRow book = catalog.lockSnapshot(preview.bookId());
        if (book == null) {
            throw new IllegalStateException("Loan references missing book");
        }
        ProjectionControlRow bookProjection = lockProjection("BOOK", preview.bookId(), !redisMode);
        requireReturnProjection(bookProjection, redisMode);
        mapper.lockStock(preview.bookId());
        LoanRow locked = mapper.lockLoan(loanId, userId);
        if (!"BORROWED".equals(locked.status())) {
            return new ReturnResult(locked, userProjection.generation(), bookProjection.generation(), false);
        }
        if (!redisMode) {
            // DB_ONLY 歸還會先使 READY 投影失效，再修改權威計數。
            mapper.markDirtyFromReady("USER", userId, now);
            mapper.markDirtyFromReady("BOOK", preview.bookId(), now);
        }
        long eventQuotaGeneration = userProjection.generation()
                + (!redisMode && "READY".equals(userProjection.state()) ? 1 : 0);
        long eventStockGeneration = bookProjection.generation()
                + (!redisMode && "READY".equals(bookProjection.state()) ? 1 : 0);
        if (mapper.returnLoan(loanId, userId, reason, now) != 1
                || mapper.releaseQuota(userId, now) != 1
                || mapper.releaseStock(preview.bookId(), now) != 1) {
            // 任一更新影響零行都代表計數不變量遭破壞；拋出例外會回滾整筆交易。
            throw new IllegalStateException("Lending invariant violation while returning loan");
        }
        LoanRow returned = mapper.findLoan(loanId, userId);
        var payload = loanPayload(returned);
        payload.put("quotaGeneration", eventQuotaGeneration);
        payload.put("stockGeneration", eventStockGeneration);
        payload.put("accountVersion", mapper.accountVersion(userId));
        payload.put("stockVersion", mapper.stockVersion(preview.bookId()));
        outbox.append("Loan", returned.loanId(), "BookReturned", returned.version(), payload, false);
        return new ReturnResult(returned, userProjection.generation(), bookProjection.generation(), true);
    }

    private ProjectionControlRow lockProjection(String type, long id, boolean update) {
        return update ? mapper.lockProjectionUpdate(type, id) : mapper.lockProjectionShare(type, id);
    }

    private static void requireProjection(ProjectionControlRow projection, Long expected, boolean dbOnly) {
        if (projection == null || "REBUILDING".equals(projection.state())) {
            throw unavailable();
        }
        if (!dbOnly && (!"READY".equals(projection.state()) || expected == null
                || projection.generation() != expected)) {
            throw unavailable();
        }
    }

    private static void requireReturnProjection(ProjectionControlRow projection, boolean redisMode) {
        if (projection == null || "REBUILDING".equals(projection.state())
                || (redisMode && !"READY".equals(projection.state()))) {
            throw unavailable();
        }
    }

    private static DomainException unavailable() {
        return new DomainException(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                "Lending resource is being rebuilt", true);
    }

    private static LinkedHashMap<String, Object> loanPayload(LoanRow loan) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("requestId", Long.toUnsignedString(loan.requestId()));
        payload.put("loanId", Long.toUnsignedString(loan.loanId()));
        payload.put("userId", Long.toUnsignedString(loan.userId()));
        payload.put("bookId", Long.toUnsignedString(loan.bookId()));
        payload.put("status", loan.status());
        payload.put("borrowedAt", loan.borrowedAt());
        payload.put("dueAt", loan.dueAt());
        payload.put("returnedAt", loan.returnedAt());
        payload.put("returnReason", loan.returnReason());
        return payload;
    }

    private LocalDateTime nowDb() { return LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC); }

    public record ReturnResult(LoanRow loan, long quotaGeneration, long stockGeneration, boolean changed) { }
}
