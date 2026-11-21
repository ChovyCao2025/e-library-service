package com.elibrary.shared.web.security;

import com.elibrary.shared.web.api.ErrorCode;
import com.elibrary.shared.web.error.DomainException;

/**
 * 保存单一 HTTP 请求的可信使用者身分。生命周期由 {@link TrustedIdentityFilter} 管理，
 * 应用服务只透过 require 方法取得已验证的身分。
 */
public final class UserContext {
    private static final ThreadLocal<UserPrincipal> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    /** 由身分过滤器在请求进入时设定目前使用者。 */
    static void set(UserPrincipal principal) {
        CURRENT.set(principal);
    }

    /** 在请求结束时清除执行绪绑定资料，防止容器重用执行绪时泄漏身分。 */
    static void clear() {
        CURRENT.remove();
    }

    /** 取得已验证身分；请求未带可信身分时抛出 401 业务例外。 */
    public static UserPrincipal require() {
        var principal = CURRENT.get();
        if (principal == null) {
            throw DomainException.of(ErrorCode.UNAUTHENTICATED);
        }
        return principal;
    }

    /** 取得管理员身分；一般使用者会收到 403 业务例外。 */
    public static UserPrincipal requireAdmin() {
        var principal = require();
        if (!principal.isAdmin()) {
            throw DomainException.of(ErrorCode.FORBIDDEN);
        }
        return principal;
    }
}
