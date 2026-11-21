package com.elibrary.lending.interfaces;

import com.elibrary.lending.domain.LoanStatus;
import com.elibrary.lending.domain.ReturnReason;
import com.elibrary.lending.infrastructure.LoanRow;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** 借阅 API 的请求与回应模型。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LendingDtos {
    /** 建立借阅的输入资料。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class BorrowRequest {
        /** 欲借阅书籍的识别码，由 JSON 数值或十进位字串转换为正整数。 */
        @NotNull
        @Positive
        private Long bookId;
    }

    /** 借阅生命周期的 API 表示。 */
    @Getter
    @RequiredArgsConstructor
    public static final class LoanResponse {
        /** 来源幂等请求的雪花识别码，以十进位字串输出。 */
        private final String requestId;

        /** 借阅资料的雪花识别码，以十进位字串输出。 */
        private final String loanId;

        /** 被借阅书籍的雪花识别码，以十进位字串输出。 */
        private final String bookId;

        /** 借阅状态，目前为 {@code BORROWED} 或 {@code RETURNED}。 */
        private final LoanStatus status;

        /** 借阅成功时间，以 UTC ISO-8601 格式输出。 */
        private final Instant borrowedAt;

        /** 应归还时间，以 UTC ISO-8601 格式输出。 */
        private final Instant dueAt;

        /** 实际归还时间；尚未归还时为 {@code null}。 */
        private final Instant returnedAt;

        /** 归还原因，例如 {@code MANUAL} 或 {@code EXPIRED}；尚未归还时为 {@code null}。 */
        private final ReturnReason returnReason;

        /** 将资料库 UTC 时间转为具时区语义的 API Instant。 */
        public static LoanResponse from(LoanRow row) {
            // MySQL DATETIME 栏位依专案约定保存 UTC，此处明确套用 UTC 后再输出 ISO-8601 时间。
            return new LoanResponse(Long.toUnsignedString(row.getRequestId()), Long.toUnsignedString(row.getLoanId()),
                    Long.toUnsignedString(row.getBookId()), row.getStatus(),
                    row.getBorrowedAt().toInstant(ZoneOffset.UTC), row.getDueAt().toInstant(ZoneOffset.UTC),
                    row.getReturnedAt() == null ? null : row.getReturnedAt().toInstant(ZoneOffset.UTC),
                    row.getReturnReason());
        }
    }

    /** 借阅结果及其幂等重放标记。 */
    @Getter
    @RequiredArgsConstructor
    public static final class BorrowOutcome {
        /** 本次请求对应的稳定借阅结果。 */
        private final LoanResponse loan;

        /** 是否由既有成功幂等请求重放；Controller 会据此加入重放标头。 */
        private final boolean replay;
    }

    /** 依借阅识别码寻址的分页查询结果。 */
    @Getter
    @RequiredArgsConstructor
    public static final class LoanPage {
        /** 本页依借阅识别码降幂排列的借阅资料。 */
        private final List<LoanResponse> items;

        /** 下一页应传入的 afterLoanId；已到最后一页时为 {@code null}。 */
        private final String nextLoanId;

        /** 是否仍有下一页资料。 */
        private final boolean hasMore;
    }
}
