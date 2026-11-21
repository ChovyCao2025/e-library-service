package com.elibrary.lending.domain;

import java.util.Locale;

/** Loan 从借出到归还的持久化生命周期。 */
public enum LoanStatus {
    BORROWED,
    RETURNED;

    /** 将 API 查询值转为领域状态；空值预设查询目前借阅。 */
    public static LoanStatus fromNullable(String value) {
        return value == null || value.isBlank()
                ? BORROWED
                : valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}
