package com.elibrary.shared.web;

import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.kernel.ClockProvider;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;

/**
 * 編碼並驗證不透明分頁游標。HMAC 防止客戶端竄改尋址位置，
 * fingerprint、頁面大小與有效期限則防止游標被套用到不同查詢。
 */
@Component
public class CursorCodec {
    private final ObjectMapper objectMapper;
    private final ClockProvider clock;
    private final AppProperties.Cursor properties;

    public CursorCodec(ObjectMapper objectMapper, ClockProvider clock, AppProperties appProperties) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = appProperties.cursor();
    }

    public String encode(String fingerprint, String backend, String after, int size) {
        var state = new CursorState(1, fingerprint, backend, after, size, clock.now().plus(properties.ttl()));
        try {
            String body = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(state));
            return body + "." + sign(body);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to encode cursor", exception);
        }
    }

    public CursorState decode(String cursor, String expectedFingerprint, int expectedSize) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String[] parts = cursor.split("\\.", -1);
            // 簽章比較採固定時間實作，避免洩漏有效 HMAC 的前綴資訊。
            if (parts.length != 2 || !constantTimeEquals(sign(parts[0]), parts[1])) {
                throw invalid();
            }
            var state = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[0]), CursorState.class);
            if (state.version() != 1 || !state.fingerprint().equals(expectedFingerprint)
                    || state.size() != expectedSize) {
                throw invalid();
            }
            if (!state.expiresAt().isAfter(clock.now())) {
                throw new DomainException(ErrorCode.CURSOR_EXPIRED, HttpStatus.BAD_REQUEST,
                        "Cursor has expired; restart the query", false);
            }
            return state;
        } catch (DomainException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC unavailable", exception);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static DomainException invalid() {
        return new DomainException(ErrorCode.CURSOR_INVALID, HttpStatus.BAD_REQUEST,
                "Cursor does not match this query", false);
    }

    public record CursorState(int version, String fingerprint, String backend, String after,
                              int size, Instant expiresAt) {
    }
}
