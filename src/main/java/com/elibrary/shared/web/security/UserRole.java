package com.elibrary.shared.web.security;

import java.util.Locale;

/** 服务内可辨识的使用者授权角色。 */
public enum UserRole {
    USER,
    ADMIN;

    /** 将闸道标头转为角色；缺少角色时采一般使用者权限。 */
    public static UserRole fromNullable(String value) {
        return value == null || value.isBlank()
                ? USER
                : valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}
