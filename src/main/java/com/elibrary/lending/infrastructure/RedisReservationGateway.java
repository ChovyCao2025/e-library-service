package com.elibrary.lending.infrastructure;

import com.elibrary.lending.application.ReservationGateway;
import com.elibrary.shared.kernel.RequestHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 以 Lua 實作配額與庫存的原子預占協定。每組腳本鍵都含相同 Redis Cluster hash tag，
 * 確保命令可在單一槽位執行，並同步維護計數、占用臺帳與待處理集合。
 */
@Component
public class RedisReservationGateway implements ReservationGateway {
    private static final Logger log = LoggerFactory.getLogger(RedisReservationGateway.class);

    private final StringRedisTemplate redis;
    private final RequestHasher hasher;
    private final DefaultRedisScript<String> reserveQuota = script("reserve_quota.lua");
    private final DefaultRedisScript<String> reserveStock = script("reserve_stock.lua");
    private final DefaultRedisScript<String> confirmQuota = script("confirm_quota.lua");
    private final DefaultRedisScript<String> confirmStock = script("confirm_stock.lua");
    private final DefaultRedisScript<String> cancelQuota = script("cancel_quota.lua");
    private final DefaultRedisScript<String> cancelStock = script("cancel_stock.lua");
    private final DefaultRedisScript<String> releaseQuota = script("release_quota.lua");
    private final DefaultRedisScript<String> releaseStock = script("release_stock.lua");

    public RedisReservationGateway(StringRedisTemplate redis, RequestHasher hasher) {
        this.redis = redis;
        this.hasher = hasher;
    }

    @Override
    public boolean isReady(long userId, long quotaGeneration, long bookId, long stockGeneration) {
        try {
            return ready(user(userId, quotaGeneration), quotaGeneration)
                    && ready(book(bookId, stockGeneration), stockGeneration);
        } catch (RuntimeException exception) {
            log.warn("Redis readiness check failed: {}", exception.getMessage());
            return false;
        }
    }

    @Override
    public Result reserveQuota(long userId, long generation, long requestId, long loanId, String token,
                               String requestHash, int maxActive, long expiresAtMillis) {
        String prefix = user(userId, generation);
        return execute(reserveQuota, List.of(prefix + "meta", prefix + "count", prefix + "occupied",
                        prefix + "pending", cancel(prefix, token), prefix + "idem:" + requestHash),
                generation, token, loanId, requestId, requestHash, maxActive, expiresAtMillis);
    }

    @Override
    public Result reserveStock(long bookId, long generation, long userId, long loanId, String token,
                               long expiresAtMillis) {
        String prefix = book(bookId, generation);
        return execute(reserveStock, List.of(prefix + "meta", prefix + "available", prefix + "occupied",
                        prefix + "user-loan", prefix + "pending", cancel(prefix, token)),
                generation, userId, loanId, token, expiresAtMillis);
    }

    @Override
    public Result confirmQuota(long userId, long generation, long loanId, String token) {
        String prefix = user(userId, generation);
        return execute(confirmQuota, List.of(prefix + "meta", prefix + "occupied", prefix + "pending",
                cancel(prefix, token)), generation, loanId, token);
    }

    @Override
    public Result confirmStock(long bookId, long generation, long userId, long loanId, String token) {
        String prefix = book(bookId, generation);
        return execute(confirmStock, List.of(prefix + "meta", prefix + "occupied", prefix + "pending",
                cancel(prefix, token)), generation, userId, loanId, token);
    }

    @Override
    public Result cancelQuota(long userId, long generation, long loanId, String token) {
        String prefix = user(userId, generation);
        return execute(cancelQuota, List.of(prefix + "occupied", prefix + "pending", prefix + "count",
                cancel(prefix, token)), loanId, token);
    }

    @Override
    public Result cancelStock(long bookId, long generation, long userId, long loanId, String token) {
        String prefix = book(bookId, generation);
        return execute(cancelStock, List.of(prefix + "occupied", prefix + "user-loan", prefix + "pending",
                prefix + "available", cancel(prefix, token)), userId, loanId, token);
    }

    @Override
    public Result releaseQuota(long userId, long generation, long loanId) {
        String prefix = user(userId, generation);
        return execute(releaseQuota, List.of(prefix + "meta", prefix + "occupied", prefix + "count"),
                generation, loanId);
    }

    @Override
    public Result releaseStock(long bookId, long generation, long userId, long loanId) {
        String prefix = book(bookId, generation);
        return execute(releaseStock, List.of(prefix + "meta", prefix + "occupied", prefix + "user-loan",
                prefix + "available"), generation, userId, loanId);
    }

    private boolean ready(String prefix, long generation) {
        Object state = redis.opsForHash().get(prefix + "meta", "state");
        Object actualGeneration = redis.opsForHash().get(prefix + "meta", "generation");
        return "READY".equals(state) && Long.toString(generation).equals(actualGeneration);
    }

    private Result execute(DefaultRedisScript<String> script, List<String> keys, Object... arguments) {
        try {
            String[] args = java.util.Arrays.stream(arguments).map(String::valueOf).toArray(String[]::new);
            String result = redis.execute(script, keys, (Object[]) args);
            return result == null ? Result.UNKNOWN : Result.valueOf(result);
        } catch (RuntimeException exception) {
            // Redis 逾時無法證明腳本未執行，因此回傳 UNKNOWN 交由持久狀態裁決。
            log.warn("Redis reservation command failed: {}", exception.getMessage());
            return Result.UNKNOWN;
        }
    }

    private String cancel(String prefix, String token) {
        return prefix + "cancel:" + hasher.sha256(token);
    }

    static String user(long userId, long generation) {
        // 大括號內的部分是 Cluster hash tag，同一使用者代次的鍵必定位於同槽。
        return "{u:" + Long.toUnsignedString(userId) + "}:g" + generation + ":";
    }

    static String book(long bookId, long generation) {
        // 使用 bookId 作為 hash tag，讓單本書的所有原子操作維持單槽限制。
        return "{b:" + Long.toUnsignedString(bookId) + "}:g" + generation + ":";
    }

    private static DefaultRedisScript<String> script(String name) {
        try (var input = new ClassPathResource("redis/" + name).getInputStream()) {
            return new DefaultRedisScript<>(new String(input.readAllBytes(), StandardCharsets.UTF_8), String.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load Redis script " + name, exception);
        }
    }
}
