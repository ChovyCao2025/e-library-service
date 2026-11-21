package com.elibrary.lending.application;

import com.elibrary.catalog.infrastructure.BookRow;
import com.elibrary.catalog.infrastructure.CatalogMapper;
import com.elibrary.lending.domain.LoanStatus;
import com.elibrary.lending.domain.ReturnReason;
import com.elibrary.lending.infrastructure.LendingMapper;
import com.elibrary.lending.infrastructure.LoanRow;
import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.kernel.ClockProvider;
import com.elibrary.shared.kernel.SnowflakeIdGenerator;
import com.elibrary.shared.outbox.application.OutboxWriter;
import com.elibrary.shared.outbox.domain.AggregateType;
import com.elibrary.shared.outbox.domain.DomainEventType;
import com.elibrary.shared.web.api.ErrorCode;
import com.elibrary.shared.web.error.DomainException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;

/**
 * 承载借阅与归还的权威 MySQL 交易。库存、使用者配额、Loan、幂等结果与
 * Outbox 事件共同提交，任何一步失败都会回滚整笔业务异动。
 */
@Service
public class LendingTransactions {
    private final LendingMapper mapper;
    private final CatalogMapper catalog;
    private final OutboxWriter outbox;
    private final SnowflakeIdGenerator idGenerator;
    private final ClockProvider clock;
    private final AppProperties.Lending properties;

    public LendingTransactions(LendingMapper mapper, CatalogMapper catalog, OutboxWriter outbox,
                               SnowflakeIdGenerator idGenerator, ClockProvider clock,
                               AppProperties appProperties) {
        this.mapper = mapper;
        this.catalog = catalog;
        this.outbox = outbox;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.properties = appProperties.getLending();
    }

    /**
     * 在单一交易内取得幂等请求所有权、锁定帐户与库存、建立 Loan 并写入事件。
     * MySQL 条件更新与唯一键共同保护配额、库存及同书重复借阅不变量。
     */
    @Transactional(timeout = 3)
    public BorrowResult borrow(long userId, long bookId, String idemKey) {
        long requestId = idGenerator.nextId();
        var now = nowDb();

        // 请求与 Loan 位于同一交易；后续失败时此列会一并回滚。
        // 唯一键 (user_id, idem_key) 是幂等与并发的唯一裁决点。
        int inserted = mapper.insertRequest(requestId, userId, idemKey, bookId, now);
        if (inserted == 0) {
            // 插入冲突时一次取得已提交的 Loan，不占用新的配额或库存。
            LoanRow existing = mapper.findLoanByIdempotencyKey(userId, idemKey);
            if (existing == null) {
                throw new IllegalStateException("Unable to resolve duplicate borrow request");
            }
            return replay(existing, bookId);
        }
        long loanId = idGenerator.nextId();

        // 固定锁顺序：使用者帐户 -> 书目 -> 书籍库存；归还沿用相同顺序。
        mapper.ensureAccount(userId, now);
        if (mapper.lockAccount(userId) == null) {
            throw new IllegalStateException("Lending account is missing for user " + userId);
        }
        BookRow book = catalog.lockSnapshot(bookId);
        if (book == null) {
            throw DomainException.of(ErrorCode.BOOK_NOT_FOUND);
        }
        if (mapper.lockStock(bookId) == null) {
            throw new IllegalStateException("Lending stock is missing for book " + bookId);
        }
        if (!book.getStatus().isBorrowable()) {
            throw DomainException.of(ErrorCode.BOOK_NOT_BORROWABLE);
        }

        // 影响行数就是不变量判断结果；后续失败会由交易回滚先前计数更新。
        if (mapper.occupyQuota(userId, properties.getMaxActiveLoans(), now) != 1) {
            throw DomainException.of(ErrorCode.LOAN_LIMIT_EXCEEDED);
        }
        if (mapper.occupyStock(bookId, now) != 1) {
            throw DomainException.of(ErrorCode.BOOK_SOLD_OUT);
        }

        LoanRow loan = LoanRow.borrowed(loanId, requestId, userId, bookId, now,
                now.plusDays(properties.getLoanDays()), properties.getPolicyVersion());
        try {
            // uk_user_book_active 是同一使用者重复借阅同一本书的最后一道并发保护。
            mapper.insertLoan(loan);
        } catch (DuplicateKeyException exception) {
            throw DomainException.of(ErrorCode.ALREADY_BORROWED);
        }
        var payload = loanPayload(loan);
        payload.put("accountVersion", mapper.accountVersion(userId));
        payload.put("stockVersion", mapper.stockVersion(bookId));
        outbox.append(AggregateType.LOAN, loan.getLoanId(), DomainEventType.BOOK_BORROWED,
                loan.getVersion(), payload);
        return new BorrowResult(loan, false);
    }

    /**
     * 在单一交易内完成幂等归还，只有 Loan 首次由 BORROWED 转为 RETURNED 时
     * 才释放使用者配额与书籍库存。
     */
    @Transactional(timeout = 3)
    public LoanRow returnLoan(long userId, long loanId, ReturnReason reason) {
        LoanRow preview = mapper.findLoan(loanId, userId);
        if (preview == null) {
            throw DomainException.of(ErrorCode.LOAN_NOT_FOUND);
        }
        if (preview.getStatus() != LoanStatus.BORROWED) {
            return preview;
        }
        var now = nowDb();

        // 使用与借阅一致的锁顺序，避免借阅与归还形成反向等待。
        if (mapper.lockAccount(userId) == null) {
            throw new IllegalStateException("Lending account is missing for user " + userId);
        }
        BookRow book = catalog.lockSnapshot(preview.getBookId());
        if (book == null) {
            throw new IllegalStateException("Loan references missing book");
        }
        if (mapper.lockStock(preview.getBookId()) == null) {
            throw new IllegalStateException("Loan references missing lending stock");
        }
        LoanRow locked = mapper.lockLoan(loanId, userId);
        if (locked.getStatus() != LoanStatus.BORROWED) {
            return locked;
        }

        if (mapper.returnLoan(loanId, userId, reason,
                LoanStatus.BORROWED, LoanStatus.RETURNED, now) != 1
                || mapper.releaseQuota(userId, now) != 1
                || mapper.releaseStock(preview.getBookId(), now) != 1) {
            // 任一更新失败都表示持久化计数不一致，抛出例外以回滚整笔归还。
            throw new IllegalStateException("Lending invariant violation while returning loan");
        }

        LoanRow returned = mapper.findLoan(loanId, userId);
        var payload = loanPayload(returned);
        payload.put("accountVersion", mapper.accountVersion(userId));
        payload.put("stockVersion", mapper.stockVersion(preview.getBookId()));
        outbox.append(AggregateType.LOAN, returned.getLoanId(), DomainEventType.BOOK_RETURNED,
                returned.getVersion(), payload);
        return returned;
    }

    /** 验证既有幂等请求的参数并回传其稳定 Loan。 */
    private BorrowResult replay(LoanRow loan, long bookId) {
        if (loan.getBookId() != bookId) {
            throw DomainException.of(ErrorCode.IDEMPOTENCY_KEY_REUSED, false, loan.getRequestId());
        }
        return new BorrowResult(loan, true);
    }

    /** 建立事件使用的借阅快照，避免消费者为基本资料再次查询交易表。 */
    private static LinkedHashMap<String, Object> loanPayload(LoanRow loan) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("requestId", Long.toUnsignedString(loan.getRequestId()));
        payload.put("loanId", Long.toUnsignedString(loan.getLoanId()));
        payload.put("userId", Long.toUnsignedString(loan.getUserId()));
        payload.put("bookId", Long.toUnsignedString(loan.getBookId()));
        payload.put("status", loan.getStatus());
        payload.put("borrowedAt", loan.getBorrowedAt());
        payload.put("dueAt", loan.getDueAt());
        payload.put("returnedAt", loan.getReturnedAt());
        payload.put("returnReason", loan.getReturnReason());
        return payload;
    }

    private LocalDateTime nowDb() {
        return LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
    }

    /** 借阅交易结果，标示本次呼叫建立新 Loan 或重放既有结果。 */
    @Getter
    @RequiredArgsConstructor
    public static final class BorrowResult {
        private final LoanRow loan;
        private final boolean replay;
    }
}
