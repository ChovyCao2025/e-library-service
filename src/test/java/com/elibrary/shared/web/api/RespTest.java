package com.elibrary.shared.web.api;

import com.elibrary.shared.web.error.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证统一回应的成功、失败与 JSON 契约。 */
class RespTest {
    @Test
    void successUsesStableCodeAndPreservesData() {
        var response = Resp.success("payload");

        assertThat(response.getCode()).isEqualTo(ErrorCode.SUCCESS.getCode());
        assertThat(response.getMsg()).isEqualTo(ErrorCode.SUCCESS.getMsg());
        assertThat(response.getData()).isEqualTo("payload");
        assertThat(Resp.isSuccess(response)).isTrue();
    }

    @Test
    void failureUsesErrorCodeAndDiagnosticData() {
        MDC.put("traceId", "trace-001");
        try {
            var detail = ErrorDetail.from(false, 123L);
            var response = Resp.fail(ErrorCode.BOOK_SOLD_OUT, detail);

            assertThat(response.getCode()).isEqualTo(ErrorCode.BOOK_SOLD_OUT.getCode());
            assertThat(response.getMsg()).isEqualTo(ErrorCode.BOOK_SOLD_OUT.getMsg());
            assertThat(response.getData().getTraceId()).isEqualTo("trace-001");
            assertThat(response.getData().getRequestId()).isEqualTo("123");
            assertThat(Resp.isSuccess(response)).isFalse();
        } finally {
            MDC.clear();
        }
    }

    @Test
    void responseAlwaysSerializesDataField() throws Exception {
        String json = new ObjectMapper().writeValueAsString(Resp.success());

        assertThat(json).contains("\"code\":\"0000\"")
                .contains("\"msg\":\"success\"")
                .contains("\"data\":null")
                .doesNotContain("\"success\":");
    }
}
