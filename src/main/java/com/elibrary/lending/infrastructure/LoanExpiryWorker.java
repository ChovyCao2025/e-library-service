package com.elibrary.lending.infrastructure;

import com.elibrary.lending.application.LendingService;
import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.kernel.ClockProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 掃描到期中的活躍借閱，並重用一般歸還流程釋放配額與庫存。
 * 多個 Worker 可重複選取同一筆資料，歸還交易本身提供冪等保護。
 */
@Component
public class LoanExpiryWorker {
    private static final Logger log = LoggerFactory.getLogger(LoanExpiryWorker.class);
    private final LendingMapper mapper;
    private final LendingService lending;
    private final ClockProvider clock;
    private final AppProperties properties;

    public LoanExpiryWorker(LendingMapper mapper, LendingService lending, ClockProvider clock,
                            AppProperties properties) {
        this.mapper = mapper;
        this.lending = lending;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.lending.expiry-interval:30s}")
    public void expireLoans() {
        if (!workerEnabled()) return;
        var now = LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
        for (LoanRow loan : mapper.findExpired(now, properties.lending().expiryBatch())) {
            try {
                lending.returnLoan(loan.userId(), loan.loanId(), "EXPIRED");
            } catch (RuntimeException exception) {
                log.warn("Loan expiry failed for {}: {}", loan.loanId(), exception.getMessage());
            }
        }
    }

    private boolean workerEnabled() {
        return "all".equalsIgnoreCase(properties.role()) || "worker".equalsIgnoreCase(properties.role());
    }
}
