package com.elibrary.shared.kernel;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 建立穩定的請求指紋，用於驗證同一冪等鍵是否被套用到不同業務參數。 */
@Component
public class RequestHasher {
    public String borrow(long bookId) {
        return sha256("borrow:v1:bookId=" + Long.toUnsignedString(bookId));
    }

    public String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
