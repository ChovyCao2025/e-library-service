package com.elibrary.lending.application;

import com.elibrary.lending.infrastructure.LendingMapper;
import com.elibrary.lending.infrastructure.LoanRow;
import com.elibrary.lending.infrastructure.ProjectionControlRow;
import com.elibrary.shared.kernel.ClockProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 提供投影重建所需的短資料庫交易。Redis 快照由 Worker 在 {@link #claim} 提交後、
 * {@link #complete} 執行前寫入，避免資料庫交易涵蓋網路操作。
 */
@Service
public class ProjectionRebuildService {
    private final LendingMapper mapper;
    private final ClockProvider clock;

    public ProjectionRebuildService(LendingMapper mapper, ClockProvider clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProjectionControlRow> candidates(int limit) {
        return mapper.findRebuildCandidates(nowDb(), limit);
    }

    @Transactional(timeout = 3)
    public ProjectionControlRow claim(ProjectionControlRow candidate, String owner, Duration lease) {
        // 條件更新確保資源只由一個 Worker 持有，同時允許過期租約被接管。
        var now = nowDb();
        int claimed = mapper.claimRebuild(candidate.resourceType(), candidate.resourceId(), owner,
                now.plus(lease), now);
        return claimed == 1 ? mapper.findProjection(candidate.resourceType(), candidate.resourceId()) : null;
    }

    @Transactional(readOnly = true)
    public UserSnapshot userSnapshot(long userId) {
        return new UserSnapshot(mapper.accountCount(userId), mapper.accountVersion(userId),
                mapper.activeLoansForUser(userId));
    }

    @Transactional(readOnly = true)
    public BookSnapshot bookSnapshot(long bookId) {
        return new BookSnapshot(mapper.stockAvailable(bookId), mapper.stockTotal(bookId),
                mapper.stockVersion(bookId), mapper.activeLoansForBook(bookId));
    }

    @Transactional(timeout = 3)
    public boolean complete(ProjectionControlRow control, String owner) {
        // generation 與 owner 共同構成隔離延遲重建 Worker 的防護權杖。
        return mapper.completeRebuild(control.resourceType(), control.resourceId(), control.generation(), owner, nowDb()) == 1;
    }

    private LocalDateTime nowDb() { return LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC); }

    public record UserSnapshot(int count, long version, List<LoanRow> loans) { }
    public record BookSnapshot(int available, int total, long version, List<LoanRow> loans) { }
}
