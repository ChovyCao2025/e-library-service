package com.elibrary.shared.web.security;

import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.web.api.ApiHeaders;
import com.elibrary.shared.web.error.DiagnosticContext;
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
import java.util.UUID;

/**
 * 将受信任闸道注入的身分标头转为请求执行绪上下文。正式环境必须由边界闸道
 * 移除外部同名标头并重新注入；开发模式只提供本机测试的预设身分。
 */
@Component
public class TrustedIdentityFilter extends OncePerRequestFilter {
    private static final String PRODUCTION_PROFILE = "prod";

    private final AppProperties.Auth auth;

    public TrustedIdentityFilter(AppProperties properties, Environment environment) {
        this.auth = properties.getAuth();
        if (auth.isDevMode() && Arrays.asList(environment.getActiveProfiles()).contains(PRODUCTION_PROFILE)) {
            throw new IllegalStateException("Development identity mode cannot run with the prod profile");
        }
    }

    /**
     * 建立请求追踪与使用者上下文，完成下游处理后一律清除执行绪本地资料。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(ApiHeaders.TRACE_PARENT);
        MDC.put(DiagnosticContext.TRACE_ID,
                traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId);
        try {
            var userHeader = request.getHeader(ApiHeaders.USER_ID);
            var roleHeader = request.getHeader(ApiHeaders.USER_ROLE);
            if ((userHeader == null || userHeader.isBlank()) && auth.isDevMode()) {
                userHeader = Long.toUnsignedString(auth.getDefaultUserId());
            }
            if (userHeader != null && !userHeader.isBlank()) {
                long userId = Long.parseUnsignedLong(userHeader);
                if (userId <= 0) {
                    throw new IllegalArgumentException(ApiHeaders.USER_ID + " must be positive");
                }
                UserRole role = roleHeader == null ? auth.getDefaultRole() : UserRole.fromNullable(roleHeader);
                UserContext.set(new UserPrincipal(userId, role));
            }
            response.setHeader(ApiHeaders.TRACE_ID, MDC.get(DiagnosticContext.TRACE_ID));
            chain.doFilter(request, response);
        } finally {
            // 容器会重用执行绪，必须清理 ThreadLocal 与 MDC，避免跨请求泄漏。
            UserContext.clear();
            MDC.clear();
        }
    }
}
