package com.elibrary.lending.infrastructure;

import com.elibrary.lending.application.LendingService;
import com.elibrary.lending.domain.LoanStatus;
import com.elibrary.lending.domain.ReturnReason;
import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.kernel.ClockProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 扫描到期中的活跃借阅，并重用一般归还流程释放配额与库存。
 * 多个 Worker 可重复选取同一笔资料，归还交易本身提供幂等保护。
 */
@Component
@RequiredArgsConstructor
public class LoanExpiryWorker {
    private static final Logger log = LoggerFactory.getLogger(LoanExpiryWorker.class);
    private final LendingMapper mapper;
    private final LendingService lending;
    private final ClockProvider clock;
    private final AppProperties properties;

    /** 分批寻找已到期 Loan，并以 EXPIRED 原因呼叫既有归还流程。 */
    @Scheduled(fixedDelayString = "${app.lending.expiry-interval:30s}")
    public void expireLoans() {
        if (!workerEnabled()) return;
        var now = LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
        for (LoanRow loan : mapper.findExpired(
                LoanStatus.BORROWED, now, properties.getLending().getExpiryBatch())) {
            try {
                lending.returnLoan(loan.getUserId(), loan.getLoanId(), ReturnReason.EXPIRED);
            } catch (RuntimeException exception) {
                log.warn("Loan expiry failed for {}: {}", loan.getLoanId(), exception.getMessage());
            }
        }
    }

    private boolean workerEnabled() {
        return properties.getRole().isWorkerEnabled();
    }
}
