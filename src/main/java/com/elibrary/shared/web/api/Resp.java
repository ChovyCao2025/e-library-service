package com.elibrary.shared.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 对外 API 的统一回应外层。
 *
 * <p>{@code code} 提供稳定的业务判断依据，{@code msg} 提供可读说明，
 * {@code data} 承载各端点的具体回应模型。</p>
 *
 * @param <T> 回应资料型别
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Resp<T> {
    /** 稳定业务码；成功使用 {@link ErrorCode#SUCCESS}，失败使用对应错误码。 */
    private final String code;

    /** 给呼叫端阅读的结果说明，不作为程式判断依据。 */
    private final String msg;

    /** 端点回应资料；没有资料时仍固定输出 {@code null}，维持统一 JSON 结构。 */
    private final T data;

    /** 建立带有资料的成功回应。 */
    public static <T> Resp<T> success(T data) {
        return new Resp<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), data);
    }

    /** 建立不带资料的成功回应。 */
    public static Resp<Void> success() {
        return new Resp<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), null);
    }

    /** 建立带有错误上下文的失败回应。 */
    public static <T> Resp<T> fail(ErrorCode code, String message, T data) {
        return new Resp<>(code.getCode(), message, data);
    }

    /** 使用错误码定义的预设讯息建立失败回应。 */
    public static <T> Resp<T> fail(ErrorCode code, T data) {
        return fail(code, code.getMsg(), data);
    }

    /**
     * 覆写全域略过 null 栏位的设定，确保所有 API 都固定具有 data 栏位。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public T getData() {
        return data;
    }

    /** 判断指定回应是否使用标准成功码。 */
    public static boolean isSuccess(Resp<?> response) {
        return response != null && ErrorCode.SUCCESS.getCode().equals(response.getCode());
    }
}
