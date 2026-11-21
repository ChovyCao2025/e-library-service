package com.elibrary.lending.application;

import com.elibrary.lending.domain.LoanStatus;
import com.elibrary.lending.domain.ReturnReason;
import com.elibrary.lending.infrastructure.LendingMapper;
import com.elibrary.lending.infrastructure.LoanRow;
import com.elibrary.lending.interfaces.LendingDtos;
import com.elibrary.shared.web.api.ErrorCode;
import com.elibrary.shared.web.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 借阅用例入口。写入操作交由 {@link LendingTransactions} 在单一 MySQL 交易内完成，
 * 本服务负责输入验证、DTO 转换及唯读查询。
 */
@Service
@RequiredArgsConstructor
public class LendingService {
    private final LendingTransactions transactions;
    private final LendingMapper mapper;

    /**
     * 借阅指定书籍。相同使用者、幂等键与书籍会重放既有结果；
     * 相同幂等键改用其他书籍时回传语义错误。
     */
    public LendingDtos.BorrowOutcome borrow(long userId, long bookId, String idemKey) {
        validateIdempotencyKey(idemKey);

        var result = transactions.borrow(userId, bookId, idemKey);
        return new LendingDtos.BorrowOutcome(LendingDtos.LoanResponse.from(result.getLoan()), result.isReplay());
    }

    /** 归还指定借阅；重复归还直接回传第一次归还后的稳定结果。 */
    public LendingDtos.LoanResponse returnLoan(long userId, long loanId, ReturnReason reason) {
        return LendingDtos.LoanResponse.from(transactions.returnLoan(userId, loanId, reason));
    }

    /** 依使用者范围查询单笔借阅，避免跨使用者存取。 */
    @Transactional(readOnly = true)
    public LendingDtos.LoanResponse getLoan(long userId, long loanId) {
        LoanRow loan = mapper.findLoan(loanId, userId);
        if (loan == null) {
            throw DomainException.of(ErrorCode.LOAN_NOT_FOUND);
        }
        return LendingDtos.LoanResponse.from(loan);
    }

    /** 依借阅状态列出目前使用者的借阅资料，下一页从 afterLoanId 之后继续。 */
    @Transactional(readOnly = true)
    public LendingDtos.LoanPage list(long userId, String status, Long afterLoanId, int size) {
        LoanStatus normalized;
        try {
            normalized = LoanStatus.fromNullable(status);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid loan status");
        }

        // 多读取一笔判断下一页，避免额外执行 COUNT 查询。
        List<LoanRow> rows = mapper.listLoans(userId, normalized, afterLoanId, size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = rows.subList(0, size);
        }

        String nextLoanId = null;
        if (hasMore) {
            nextLoanId = Long.toUnsignedString(rows.getLast().getLoanId());
        }
        return new LendingDtos.LoanPage(
                rows.stream().map(LendingDtos.LoanResponse::from).toList(), nextLoanId, hasMore);
    }

    /** 验证幂等键可安全储存及比较，限制为 1 至 64 个不含空白的可列印 ASCII 字元。 */
    public static void validateIdempotencyKey(String key) {
        if (key == null || key.isEmpty()) {
            throw DomainException.of(ErrorCode.MISSING_IDEMPOTENCY_KEY);
        }
        if (key.length() > 64 || key.chars().anyMatch(ch -> ch < 0x21 || ch > 0x7e)) {
            throw DomainException.of(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be 1-64 printable ASCII characters without whitespace");
        }
    }
}
