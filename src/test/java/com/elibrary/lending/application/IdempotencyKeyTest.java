package com.elibrary.lending.application;

import com.elibrary.shared.web.api.ErrorCode;
import com.elibrary.shared.web.error.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证借阅幂等键接受范围与对应的错误码。 */
class IdempotencyKeyTest {
    @Test
    void acceptsPrintableAsciiWithoutWhitespace() {
        assertThatCode(() -> LendingService.validateIdempotencyKey("borrow-1001:A"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingWhitespaceAndOversizedKeys() {
        assertCode(null, ErrorCode.MISSING_IDEMPOTENCY_KEY);
        assertCode("has space", ErrorCode.VALIDATION_FAILED);
        assertCode("a".repeat(65), ErrorCode.VALIDATION_FAILED);
    }

    private static void assertCode(String value, ErrorCode code) {
        assertThatThrownBy(() -> LendingService.validateIdempotencyKey(value))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
}
