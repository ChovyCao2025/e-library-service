package com.elibrary.shared.web;

import com.elibrary.shared.kernel.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * 將受信任閘道注入的身分標頭轉為請求執行緒上下文。正式環境必須由邊界閘道
 * 移除外部同名標頭並重新注入；開發模式只提供本機測試的預設身分。
 */
@Component
public class TrustedIdentityFilter extends OncePerRequestFilter {
    private final AppProperties.Auth auth;

    public TrustedIdentityFilter(AppProperties properties, Environment environment) {
        this.auth = properties.auth();
        if (auth.devMode() && Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("Development identity mode cannot run with the prod profile");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader("traceparent");
        MDC.put("traceId", traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId);
        try {
            var userHeader = request.getHeader("X-User-Id");
            var roleHeader = request.getHeader("X-Role");
            if ((userHeader == null || userHeader.isBlank()) && auth.devMode()) {
                userHeader = Long.toUnsignedString(auth.defaultUserId());
                roleHeader = auth.defaultRole();
            }
            if (userHeader != null && !userHeader.isBlank()) {
                long userId = Long.parseUnsignedLong(userHeader);
                if (userId <= 0) {
                    throw new IllegalArgumentException("X-User-Id must be positive");
                }
                String role = roleHeader == null ? "USER" : roleHeader.toUpperCase(Locale.ROOT);
                UserContext.set(new UserPrincipal(userId, role));
            }
            response.setHeader("X-Trace-Id", MDC.get("traceId"));
            chain.doFilter(request, response);
        } finally {
            // 容器會重用執行緒，必須清理 ThreadLocal 與 MDC，避免跨請求洩漏。
            UserContext.clear();
            MDC.clear();
        }
    }
}
