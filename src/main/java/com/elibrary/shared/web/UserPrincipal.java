package com.elibrary.shared.web;

public record UserPrincipal(long userId, String role) {
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
