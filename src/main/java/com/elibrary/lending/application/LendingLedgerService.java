package com.elibrary.lending.application;

import com.elibrary.lending.infrastructure.BorrowRequestRow;
import com.elibrary.lending.infrastructure.LendingMapper;
import com.elibrary.lending.infrastructure.ProjectionControlRow;
import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.kernel.ClockProvider;
import com.elibrary.shared.kernel.IdGenerator;
import com.elibrary.shared.web.DomainException;
import com.elibrary.shared.web.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 管理持久化借閱請求狀態機。每次狀態轉移都使用短交易，
 * 讓預占操作可以安全重試，且 Redis 呼叫期間不持有資料庫鎖。
 */
@Service
public class LendingLedgerService {
    private final LendingMapper mapper;
    private final IdGenerator ids;
    private final ClockProvider clock;
    private final AppProperties.Lending properties;

    public LendingLedgerService(LendingMapper mapper, IdGenerator ids, ClockProvider clock, AppProperties appProperties) {
        this.mapper = mapper;
        this.ids = ids;
        this.clock = clock;
        this.properties = appProperties.lending();
    }

    @Transactional(timeout = 3)
    public void ensureUser(long userId) {
        var now = nowDb();
        mapper.ensureAccount(userId, now);
        mapper.ensureUserProjection(userId, now);
    }

    @Transactional(readOnly = true)
    public Generations generations(long userId, long bookId) {
        return new Generations(mapper.findProjection("USER", userId), mapper.findProjection("BOOK", bookId));
    }

    @Transactional(timeout = 3)
    public Attempt acquire(long userId, long bookId, String idemKey, String requestHash,
                           String mode, Long quotaGeneration, Long stockGeneration) {
        BorrowRequestRow existing = mapper.findRequest(userId, idemKey);
        if (existing != null) {
            validateFingerprint(existing, bookId, requestHash);
            if ("RETRYABLE".equals(existing.status())) {
                // 新權杖會隔離所有舊 attempt 的延遲工作。
                String token = existing.requestId() + ":" + (existing.attempt() + 1) + ":" + ids.nextId();
                var now = nowDb();
                int restarted = mapper.restartRequest(existing.requestId(), token, mode, quotaGeneration, stockGeneration,
                        now.plus(properties.attemptLease()), now);
                return new Attempt(mapper.findRequest(userId, idemKey), restarted == 1, false);
            }
            return new Attempt(existing, false, "SUCCEEDED".equals(existing.status()));
        }
        long requestId = ids.nextId();
        long loanId = ids.nextId();
        String token = requestId + ":1:" + ids.nextId();
        var now = nowDb();
        int inserted = mapper.insertRequest(requestId, userId, idemKey, bookId, requestHash, loanId, token,
                mode, quotaGeneration, stockGeneration, now.plus(properties.attemptLease()), now);

        // INSERT IGNORE 解決併發寫入競爭；各呼叫端隨後讀取並驗證唯一勝出的資料列。
        BorrowRequestRow row = mapper.findRequest(userId, idemKey);
        validateFingerprint(row, bookId, requestHash);
        return new Attempt(row, inserted == 1, inserted == 0 && "SUCCEEDED".equals(row.status()));
    }

    @Transactional(timeout = 3)
    public void beginRelease(long requestId, String token, ErrorCode result) {
        mapper.markReleasing(requestId, token, result.name(), nowDb());
    }

    @Transactional(timeout = 3)
    public void completeRelease(long requestId, String token) {
        mapper.markRetryable(requestId, token, nowDb());
    }

    @Transactional(timeout = 3)
    public void settle(long requestId, String token, boolean quota, boolean stock) {
        var now = nowDb();
        if (quota) mapper.settleQuota(requestId, token, now);
        if (stock) mapper.settleStock(requestId, token, now);
    }

    private static void validateFingerprint(BorrowRequestRow request, long bookId, String hash) {
        if (request.bookId() != bookId || !request.requestHash().equals(hash)) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED, HttpStatus.UNPROCESSABLE_ENTITY,
                    "Idempotency-Key was already used for different parameters", false, request.requestId());
        }
    }

    private LocalDateTime nowDb() { return LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC); }

    public record Generations(ProjectionControlRow user, ProjectionControlRow book) { }
    public record Attempt(BorrowRequestRow request, boolean owner, boolean replay) { }
}
