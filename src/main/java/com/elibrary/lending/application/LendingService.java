package com.elibrary.lending.application;

import com.elibrary.lending.infrastructure.BorrowRequestRow;
import com.elibrary.lending.infrastructure.LendingMapper;
import com.elibrary.lending.infrastructure.LoanRow;
import com.elibrary.lending.interfaces.LendingDtos;
import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.kernel.ClockProvider;
import com.elibrary.shared.kernel.RequestHasher;
import com.elibrary.shared.web.CursorCodec;
import com.elibrary.shared.web.DomainException;
import com.elibrary.shared.web.ErrorCode;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 協調持久化請求臺帳與 Redis 預占層之間的借閱流程。Redis 僅負責准入控制，
 * MySQL 交易才是最終權威；外部預占呼叫位於資料庫交易之外，以限制持鎖時間。
 */
@Service
public class LendingService {
    private static final Logger log = LoggerFactory.getLogger(LendingService.class);
    private final LendingLedgerService ledger;
    private final LendingTransactions transactions;
    private final LendingMapper mapper;
    private final ReservationGateway reservations;
    private final RequestHasher hasher;
    private final ClockProvider clock;
    private final AppProperties.Lending properties;
    private final CursorCodec cursors;

    public LendingService(LendingLedgerService ledger, LendingTransactions transactions, LendingMapper mapper,
                          ReservationGateway reservations, RequestHasher hasher, ClockProvider clock,
                          AppProperties appProperties, CursorCodec cursors) {
        this.ledger = ledger;
        this.transactions = transactions;
        this.mapper = mapper;
        this.reservations = reservations;
        this.hasher = hasher;
        this.clock = clock;
        this.properties = appProperties.lending();
        this.cursors = cursors;
    }

    public LendingDtos.BorrowOutcome borrow(long userId, long bookId, String idemKey) {
        validateIdempotencyKey(idemKey);
        String hash = hasher.borrow(bookId);

        // 先查詢持久化冪等結果，再存取 Redis，確保故障期間的重試語義仍然穩定。
        BorrowRequestRow previous = mapper.findRequest(userId, idemKey);
        if (previous != null) {
            if (previous.bookId() != bookId || !previous.requestHash().equals(hash)) {
                throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED, HttpStatus.UNPROCESSABLE_ENTITY,
                        "Idempotency-Key was already used for different parameters", false, previous.requestId());
            }
            if ("SUCCEEDED".equals(previous.status())) {
                return new LendingDtos.BorrowOutcome(LendingDtos.LoanResponse.from(requireLoanByRequest(previous)), true);
            }
            if (!"RETRYABLE".equals(previous.status())) {
                throw inProgress(previous.requestId());
            }
        }

        ledger.ensureUser(userId);
        var generations = ledger.generations(userId, bookId);
        if (generations.book() == null) {
            throw DomainException.notFound(ErrorCode.BOOK_NOT_FOUND, "Book was not found");
        }
        boolean redis = properties.redisEnabled()
                && "READY".equals(generations.user().state()) && "READY".equals(generations.book().state())
                && reservations.isReady(userId, generations.user().generation(), bookId, generations.book().generation());
        if (!redis && !properties.dbOnlyOnRedisFailure()) {
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "Reservation service is unavailable", true);
        }

        // 准入模式與投影代次在本次 attempt 存續期間保持不變。
        var attempt = ledger.acquire(userId, bookId, idemKey, hash, redis ? "REDIS" : "DB_ONLY",
                redis ? generations.user().generation() : null, redis ? generations.book().generation() : null);
        if (attempt.replay()) {
            return new LendingDtos.BorrowOutcome(LendingDtos.LoanResponse.from(requireLoanByRequest(attempt.request())), true);
        }
        if (!attempt.owner()) {
            throw inProgress(attempt.request().requestId());
        }

        BorrowRequestRow request = attempt.request();
        boolean quotaReserved = false;
        boolean stockReserved = false;
        try {
            // 雙預占用於減少無效的資料庫競爭；finalizeBorrow 仍會重新檢查全部業務不變量。
            if (redis) {
                long expiry = request.leaseUntil().toInstant(ZoneOffset.UTC).toEpochMilli();
                var quota = reservations.reserveQuota(userId, request.quotaGeneration(), request.requestId(),
                        request.loanId(), request.attemptToken(), hash, properties.maxActiveLoans(), expiry);
                quotaReserved = accepted(quota);
                if (!quotaReserved) throw reservationError(quota, request.requestId());
                var stock = reservations.reserveStock(bookId, request.stockGeneration(), userId,
                        request.loanId(), request.attemptToken(), expiry);
                stockReserved = accepted(stock);
                if (!stockReserved) throw reservationError(stock, request.requestId());
            }
            LoanRow loan = transactions.finalizeBorrow(request.requestId(), request.attemptToken());
            if (redis) {
                // 借閱一旦提交即回傳成功；Redis 結算進度可由背景 Worker 接續修復。
                boolean quotaSettled = accepted(reservations.confirmQuota(userId, request.quotaGeneration(),
                        request.loanId(), request.attemptToken()));
                boolean stockSettled = accepted(reservations.confirmStock(bookId, request.stockGeneration(), userId,
                        request.loanId(), request.attemptToken()));
                try {
                    ledger.settle(request.requestId(), request.attemptToken(), quotaSettled, stockSettled);
                } catch (RuntimeException exception) {
                    log.warn("Borrow committed but reservation settlement progress could not be recorded for request {}: {}",
                            request.requestId(), exception.getMessage());
                }
            }
            return new LendingDtos.BorrowOutcome(LendingDtos.LoanResponse.from(loan), false);
        } catch (DomainException exception) {
            compensate(request, quotaReserved, stockReserved, exception.code());
            throw exception;
        } catch (RuntimeException exception) {
            compensate(request, quotaReserved, stockReserved, ErrorCode.SERVICE_UNAVAILABLE);
            throw exception;
        }
    }

    public LendingDtos.LoanResponse returnLoan(long userId, long loanId, String reason) {
        // Loan 的 bookId 建立後不再變更；交易會依固定鎖順序重新讀取權威狀態。
        LoanRow preview = mapper.findLoan(loanId, userId);
        if (preview == null) throw DomainException.notFound(ErrorCode.LOAN_NOT_FOUND, "Loan was not found");
        if (!"BORROWED".equals(preview.status())) return LendingDtos.LoanResponse.from(preview);
        var generations = ledger.generations(userId, preview.bookId());
        boolean redis = properties.redisEnabled() && generations.user() != null && generations.book() != null
                && "READY".equals(generations.user().state()) && "READY".equals(generations.book().state())
                && reservations.isReady(userId, generations.user().generation(), preview.bookId(), generations.book().generation());
        var result = transactions.returnLoan(userId, loanId, reason, redis);
        if (redis && result.changed()) {
            reservations.releaseQuota(userId, result.quotaGeneration(), loanId);
            reservations.releaseStock(preview.bookId(), result.stockGeneration(), userId, loanId);
        }
        return LendingDtos.LoanResponse.from(result.loan());
    }

    @Transactional(readOnly = true)
    public LendingDtos.LoanResponse getLoan(long userId, long loanId) {
        LoanRow loan = mapper.findLoan(loanId, userId);
        if (loan == null) throw DomainException.notFound(ErrorCode.LOAN_NOT_FOUND, "Loan was not found");
        return LendingDtos.LoanResponse.from(loan);
    }

    @Transactional(readOnly = true)
    public LendingDtos.RequestStatusResponse getRequest(long userId, long requestId) {
        BorrowRequestRow request = mapper.findRequestById(requestId, userId);
        if (request == null) throw DomainException.notFound(ErrorCode.REQUEST_NOT_FOUND, "Borrow request was not found");
        return LendingDtos.RequestStatusResponse.from(request);
    }

    @Transactional(readOnly = true)
    public LendingDtos.LoanPage list(long userId, String status, String cursor, int size) {
        String normalized = status == null ? "BORROWED" : status.toUpperCase();
        if (!List.of("BORROWED", "RETURNED").contains(normalized)) throw new IllegalArgumentException("Invalid loan status");
        String fingerprint = hasher.sha256("loans|" + userId + "|" + normalized + "|" + size);
        var state = cursors.decode(cursor, fingerprint, size);
        LocalDateTime afterTime = null;
        Long afterId = null;
        if (state != null) {
            // 游標攜帶複合尋址鍵，對應資料庫索引的降冪掃描順序。
            String[] parts = state.after().split("_", 2);
            afterTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(Long.parseLong(parts[0])), ZoneOffset.UTC);
            afterId = Long.parseUnsignedLong(parts[1]);
        }
        List<LoanRow> rows = mapper.listLoans(userId, normalized, afterTime, afterId, size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) rows = rows.subList(0, size);
        String next = null;
        if (hasMore) {
            LoanRow last = rows.getLast();
            String after = last.borrowedAt().toInstant(ZoneOffset.UTC).toEpochMilli() + "_" + Long.toUnsignedString(last.loanId());
            next = cursors.encode(fingerprint, "DB", after, size);
        }
        return new LendingDtos.LoanPage(rows.stream().map(LendingDtos.LoanResponse::from).toList(), next, hasMore);
    }

    private void compensate(BorrowRequestRow request, boolean quota, boolean stock, ErrorCode result) {
        // 先持久化 RELEASING，程序中斷後背景 Worker 才能接續清理。
        ledger.beginRelease(request.requestId(), request.attemptToken(), result);
        if ("REDIS".equals(request.admissionMode())) {
            if (stock) reservations.cancelStock(request.bookId(), request.stockGeneration(), request.userId(),
                    request.loanId(), request.attemptToken());
            if (quota) reservations.cancelQuota(request.userId(), request.quotaGeneration(), request.loanId(),
                    request.attemptToken());
        }
        ledger.completeRelease(request.requestId(), request.attemptToken());
    }

    private LoanRow requireLoanByRequest(BorrowRequestRow request) {
        LoanRow loan = mapper.findLoanByRequest(request.requestId());
        if (loan == null) throw new IllegalStateException("Succeeded request has no loan");
        return loan;
    }

    private static boolean accepted(ReservationGateway.Result result) {
        return result == ReservationGateway.Result.APPLIED || result == ReservationGateway.Result.IDEMPOTENT;
    }

    private static DomainException reservationError(ReservationGateway.Result result, long requestId) {
        return switch (result) {
            case LIMIT_EXCEEDED -> new DomainException(ErrorCode.LOAN_LIMIT_EXCEEDED, HttpStatus.CONFLICT,
                    "Active loan limit reached", false, requestId);
            case SOLD_OUT -> new DomainException(ErrorCode.BOOK_SOLD_OUT, HttpStatus.CONFLICT,
                    "No lending seat is available", false, requestId);
            case ALREADY_BORROWED -> new DomainException(ErrorCode.ALREADY_BORROWED, HttpStatus.CONFLICT,
                    "User already has an active loan for this book", false, requestId);
            default -> new DomainException(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "Reservation state is unavailable", true, requestId);
        };
    }

    private static DomainException inProgress(long requestId) {
        return new DomainException(ErrorCode.REQUEST_IN_PROGRESS, HttpStatus.CONFLICT,
                "A borrow attempt is already in progress", true, requestId);
    }

    public static void validateIdempotencyKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new DomainException(ErrorCode.MISSING_IDEMPOTENCY_KEY, HttpStatus.BAD_REQUEST,
                    "Idempotency-Key is required", false);
        }
        if (key.length() > 64 || key.chars().anyMatch(ch -> ch < 0x21 || ch > 0x7e)) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 1-64 printable ASCII characters without whitespace", false);
        }
    }
}
