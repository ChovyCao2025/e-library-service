package com.elibrary.shared.web.error;

import com.elibrary.shared.web.api.ApiHeaders;
import com.elibrary.shared.web.api.ErrorCode;
import com.elibrary.shared.web.api.Resp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.validation.ConstraintViolationException;

/** 将领域错误与框架例外统一转换为 {@link Resp} 失败回应。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 将领域例外保留的错误码、重试旗标与请求识别码原样带入回应。 */
    @ExceptionHandler(DomainException.class)
    Resp<ErrorDetail> handleDomain(DomainException exception) {
        return failure(exception.getCode(), exception.getMessage(),
                exception.isRetryable(), exception.getRequestId());
    }

    /** 将参数验证、JSON 解析与格式转换错误收敛为相同的验证错误。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
            HttpMessageNotReadableException.class, IllegalArgumentException.class,
            ConstraintViolationException.class})
    Resp<ErrorDetail> handleValidation(Exception exception) {
        return failure(ErrorCode.VALIDATION_FAILED, false, null);
    }

    /** 区分缺少幂等键与其他必要标头，提供稳定的对外错误码。 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    Resp<ErrorDetail> handleMissingHeader(MissingRequestHeaderException exception) {
        var code = ApiHeaders.IDEMPOTENCY_KEY.equalsIgnoreCase(exception.getHeaderName())
                ? ErrorCode.MISSING_IDEMPOTENCY_KEY : ErrorCode.VALIDATION_FAILED;
        return failure(code, false, null);
    }

    /** 将未在应用层拦截的资料库约束冲突转换为安全的对外讯息。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    Resp<ErrorDetail> handleIntegrity(DataIntegrityViolationException exception) {
        log.warn("Database constraint rejected request", exception);
        return failure(ErrorCode.VERSION_CONFLICT, false, null);
    }

    /** 记录非预期例外的完整资讯，对外只回传固定讯息以避免泄漏内部细节。 */
    @ExceptionHandler(Exception.class)
    Resp<ErrorDetail> handleUnexpected(Exception exception) {
        log.error("Unhandled request error", exception);
        return failure(ErrorCode.INTERNAL_ERROR, false, null);
    }

    /** 使用错误码定义的预设讯息组装失败回应。 */
    private static Resp<ErrorDetail> failure(ErrorCode code, boolean retryable, Long requestId) {
        return failure(code, code.getMsg(), retryable, requestId);
    }

    /** 组装错误码、可读讯息与诊断资料。 */
    private static Resp<ErrorDetail> failure(ErrorCode code, String message,
                                             boolean retryable, Long requestId) {
        return Resp.fail(code, message, ErrorDetail.from(retryable, requestId));
    }
}
