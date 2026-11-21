package com.elibrary.shared.web.error;

import com.elibrary.shared.web.api.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证结果码定义完整、唯一，且例外可稳定映射为统一回应。 */
class ErrorCodeTest {
    @Test
    void definitionsHaveUniqueCodesAndRequiredMetadata() {
        assertThat(ErrorCode.values())
                .extracting(ErrorCode::getCode)
                .doesNotHaveDuplicates()
                .allMatch(code -> code != null && !code.isBlank());
        assertThat(ErrorCode.values())
                .extracting(ErrorCode::getMsg)
                .allMatch(message -> message != null && !message.isBlank());
    }

    @Test
    void exceptionHandlerUsesCodeAndMessageDefinition() {
        var handler = new GlobalExceptionHandler();
        var response = handler.handleDomain(DomainException.of(ErrorCode.BOOK_SOLD_OUT));

        assertThat(response.getCode()).isEqualTo(ErrorCode.BOOK_SOLD_OUT.getCode());
        assertThat(response.getMsg()).isEqualTo(ErrorCode.BOOK_SOLD_OUT.getMsg());
        assertThat(response.getData()).isNotNull();
    }
}
