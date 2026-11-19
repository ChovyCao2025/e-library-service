package com.elibrary.shared.web;

import org.springframework.http.HttpStatus;

/**
 * 保存單一 HTTP 請求的可信使用者身分。生命週期由 {@link TrustedIdentityFilter} 管理，
 * 應用服務只透過 require 方法取得已驗證的身分。
 */
public final class UserContext {
    private static final ThreadLocal<UserPrincipal> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    static void set(UserPrincipal principal) {
        CURRENT.set(principal);
    }

    static void clear() {
        CURRENT.remove();
    }

    public static UserPrincipal require() {
        var principal = CURRENT.get();
        if (principal == null) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED,
                    "A trusted user identity is required", false);
        }
        return principal;
    }

    public static UserPrincipal requireAdmin() {
        var principal = require();
        if (!principal.isAdmin()) {
            throw new DomainException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN,
                    "Administrator role is required", false);
        }
        return principal;
    }
}
