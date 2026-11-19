package com.elibrary.lending.infrastructure;

import com.elibrary.lending.application.LendingLedgerService;
import com.elibrary.lending.application.ReservationGateway;
import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.kernel.ClockProvider;
import com.elibrary.shared.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 修復租約逾期或 Redis 結算未完成的借閱請求。所有外部命令皆可重送；
 * 只有取得明確終態後才推進持久化臺帳，UNKNOWN 會保留給下一輪重試。
 */
@Component
public class LendingRecoveryWorker {
    private static final Logger log = LoggerFactory.getLogger(LendingRecoveryWorker.class);
    private final LendingMapper mapper;
    private final LendingLedgerService ledger;
    private final ReservationGateway reservations;
    private final ClockProvider clock;
    private final AppProperties properties;

    public LendingRecoveryWorker(LendingMapper mapper, LendingLedgerService ledger,
                                 ReservationGateway reservations, ClockProvider clock, AppProperties properties) {
        this.mapper = mapper;
        this.ledger = ledger;
        this.reservations = reservations;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.lending.recovery-interval:10s}")
    public void recoverRequests() {
        if (!workerEnabled()) return;
        var now = LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
        for (BorrowRequestRow request : mapper.findRequestsForRecovery(now, 100)) {
            try {
                recover(request);
            } catch (RuntimeException exception) {
                log.warn("Borrow request recovery failed for {}: {}", request.requestId(), exception.getMessage());
            }
        }
    }

    private void recover(BorrowRequestRow request) {
        if ("SUCCEEDED".equals(request.status())) {
            if ("DB_ONLY".equals(request.admissionMode())) {
                ledger.settle(request.requestId(), request.attemptToken(), true, true);
                return;
            }
            LoanRow loan = mapper.findLoanByRequest(request.requestId());
            if (loan == null) throw new IllegalStateException("Succeeded request has no loan");
            ReservationGateway.Result quota;
            ReservationGateway.Result stock;
            // Loan 狀態決定要補做確認或釋放，避免已歸還借閱重新占用資源。
            if ("BORROWED".equals(loan.status())) {
                quota = reservations.confirmQuota(request.userId(), request.quotaGeneration(), request.loanId(),
                        request.attemptToken());
                stock = reservations.confirmStock(request.bookId(), request.stockGeneration(), request.userId(),
                        request.loanId(), request.attemptToken());
            } else {
                quota = reservations.releaseQuota(request.userId(), request.quotaGeneration(), request.loanId());
                stock = reservations.releaseStock(request.bookId(), request.stockGeneration(), request.userId(),
                        request.loanId());
            }
            ledger.settle(request.requestId(), request.attemptToken(), terminal(quota), terminal(stock));
            return;
        }

        if ("PROCESSING".equals(request.status())) {
            // 先以資料庫狀態阻止逾期處理者遲到提交，再取消外部預占。
            ledger.beginRelease(request.requestId(), request.attemptToken(), ErrorCode.SERVICE_UNAVAILABLE);
        }
        if ("DB_ONLY".equals(request.admissionMode())) {
            ledger.completeRelease(request.requestId(), request.attemptToken());
            return;
        }
        var stock = reservations.cancelStock(request.bookId(), request.stockGeneration(), request.userId(),
                request.loanId(), request.attemptToken());
        var quota = reservations.cancelQuota(request.userId(), request.quotaGeneration(), request.loanId(),
                request.attemptToken());
        if (terminal(stock) && terminal(quota)) {
            // 兩項資源都取得明確結果後，請求才可回到 RETRYABLE。
            ledger.completeRelease(request.requestId(), request.attemptToken());
        }
    }

    private static boolean terminal(ReservationGateway.Result result) {
        return result != ReservationGateway.Result.UNKNOWN;
    }

    private boolean workerEnabled() {
        return "all".equalsIgnoreCase(properties.role()) || "worker".equalsIgnoreCase(properties.role());
    }
}
