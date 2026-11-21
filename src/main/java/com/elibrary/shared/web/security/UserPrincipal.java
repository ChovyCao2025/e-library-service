package com.elibrary.shared.web.security;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 已验证使用者在单次请求中的身分资讯。 */
@Getter
@RequiredArgsConstructor
public final class UserPrincipal {
    private final long userId;
    private final UserRole role;

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }
}
