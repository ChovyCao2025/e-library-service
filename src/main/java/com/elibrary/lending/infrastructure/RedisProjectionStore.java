package com.elibrary.lending.infrastructure;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依 MySQL 權威快照重建 Redis 投影。重建使用新的 generation 命名空間，
 * 並在所有計數與占用臺帳寫完後才發布 READY 中繼資料。
 */
@Component
public class RedisProjectionStore {
    private final StringRedisTemplate redis;

    public RedisProjectionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean available() {
        RedisConnection connection = null;
        try {
            connection = redis.getConnectionFactory().getConnection();
            return "PONG".equalsIgnoreCase(connection.ping());
        } catch (RuntimeException exception) {
            return false;
        } finally {
            if (connection != null) connection.close();
        }
    }

    public void rebuildUser(long userId, long generation, int activeCount, long accountVersion,
                            List<LoanRow> loans) {
        String prefix = RedisReservationGateway.user(userId, generation);
        List<String> keys = List.of(prefix + "meta", prefix + "count", prefix + "occupied", prefix + "pending");
        redis.delete(keys);
        redis.opsForValue().set(prefix + "count", Integer.toString(activeCount));
        if (!loans.isEmpty()) {
            Map<String, String> occupied = new LinkedHashMap<>();
            loans.forEach(loan -> occupied.put(Long.toUnsignedString(loan.loanId()), "snapshot|COMMITTED"));
            redis.opsForHash().putAll(prefix + "occupied", occupied);
        }
        // READY 必須最後寫入，避免借閱流程讀到尚未完成的快照。
        redis.opsForHash().putAll(prefix + "meta", Map.of(
                "state", "READY",
                "generation", Long.toString(generation),
                "dbVersion", Long.toString(accountVersion)));
    }

    public void rebuildBook(long bookId, long generation, int available, int total, long stockVersion,
                            List<LoanRow> loans) {
        String prefix = RedisReservationGateway.book(bookId, generation);
        List<String> keys = List.of(prefix + "meta", prefix + "available", prefix + "occupied",
                prefix + "user-loan", prefix + "pending");
        redis.delete(keys);
        redis.opsForValue().set(prefix + "available", Integer.toString(available));
        if (!loans.isEmpty()) {
            Map<String, String> occupied = new LinkedHashMap<>();
            Map<String, String> userLoans = new LinkedHashMap<>();
            loans.forEach(loan -> {
                String loanId = Long.toUnsignedString(loan.loanId());
                String userId = Long.toUnsignedString(loan.userId());
                occupied.put(loanId, userId + "|snapshot|COMMITTED");
                userLoans.put(userId, loanId);
            });
            redis.opsForHash().putAll(prefix + "occupied", occupied);
            redis.opsForHash().putAll(prefix + "user-loan", userLoans);
        }
        // 發布 meta 前，available、occupied 與 user-loan 已來自同一資料庫快照。
        redis.opsForHash().putAll(prefix + "meta", Map.of(
                "state", "READY",
                "generation", Long.toString(generation),
                "dbVersion", Long.toString(stockVersion),
                "total", Integer.toString(total)));
    }
}
