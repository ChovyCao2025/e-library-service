package com.elibrary.shared.web.error;

import com.elibrary.shared.web.api.ErrorCode;
import com.elibrary.shared.web.api.Resp;
import lombok.Getter;

/**
 * 可预期的业务例外，携带稳定错误码、可读细节与重试语义。
 * {@link GlobalExceptionHandler} 会将此物件转换为统一的 {@link Resp} 失败回应。
 */
@Getter
public class DomainException extends RuntimeException {
    private final ErrorCode code;
    private final boolean retryable;
    private final Long requestId;

    /** 建立不关联特定借阅请求的业务例外。 */
    public DomainException(ErrorCode code, String detail, boolean retryable) {
        this(code, detail, retryable, null);
    }

    /** 建立可供呼叫端追踪借阅请求的业务例外。 */
    public DomainException(ErrorCode code, String detail, boolean retryable, Long requestId) {
        super(detail);
        this.code = code;
        this.retryable = retryable;
        this.requestId = requestId;
    }

    /** 使用错误码定义的预设讯息建立业务例外。 */
    public static DomainException of(ErrorCode code) {
        return new DomainException(code, code.getMsg(), false);
    }

    /** 使用更具体的安全讯息建立业务例外。 */
    public static DomainException of(ErrorCode code, String detail) {
        return new DomainException(code, detail, false);
    }

    /** 使用错误码预设讯息建立可追踪特定业务请求的例外。 */
    public static DomainException of(ErrorCode code, boolean retryable, Long requestId) {
        return new DomainException(code, code.getMsg(), retryable, requestId);
    }
}
