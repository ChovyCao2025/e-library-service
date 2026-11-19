package com.elibrary.lending.application;

import com.elibrary.shared.web.DomainException;
import com.elibrary.shared.web.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
