package com.elibrary.lending.infrastructure;

import com.elibrary.lending.domain.LoanStatus;
import com.elibrary.lending.domain.ReturnReason;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 借阅生命周期的持久化查询模型。 */
@Getter
@Setter
@NoArgsConstructor
public class LoanRow {
    // 借阅与来源请求、使用者、书籍之间的固定关联。
    private long loanId;
    private long requestId;
    private long userId;
    private long bookId;

    // 借阅状态、有效期间及归还结果。
    private LoanStatus status;
    private Integer activeKey;
    private LocalDateTime borrowedAt;
    private LocalDateTime dueAt;
    private LocalDateTime returnedAt;
    private ReturnReason returnReason;
    private String policyVersion;

    // 聚合版本与稽核时间，用于事件排序及追踪。
    private long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 建立首次借出的持久化资料；栏位值同时供 INSERT、API 回应与事件使用。 */
    public static LoanRow borrowed(long loanId, long requestId, long userId, long bookId,
                                   LocalDateTime borrowedAt, LocalDateTime dueAt,
                                   String policyVersion) {
        LoanRow loan = new LoanRow();
        loan.setLoanId(loanId);
        loan.setRequestId(requestId);
        loan.setUserId(userId);
        loan.setBookId(bookId);
        loan.setStatus(LoanStatus.BORROWED);
        loan.setBorrowedAt(borrowedAt);
        loan.setDueAt(dueAt);
        loan.setPolicyVersion(policyVersion);
        loan.setCreatedAt(borrowedAt);
        loan.setUpdatedAt(borrowedAt);
        return loan;
    }
}
