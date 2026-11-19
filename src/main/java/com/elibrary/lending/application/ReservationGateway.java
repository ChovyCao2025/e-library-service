package com.elibrary.lending.application;

/**
 * 依資源代次隔離的 Redis 預占協定連接埠。呼叫端會在逾時或程序重啟後重送命令，
 * 因此實作必須保證每項操作都具備冪等性。
 */
public interface ReservationGateway {
    /** 回傳值代表協定結果；{@code UNKNOWN} 必須交由資料庫權威狀態進行裁決。 */
    enum Result { APPLIED, IDEMPOTENT, NOT_READY, CANCELLED, LIMIT_EXCEEDED, SOLD_OUT, ALREADY_BORROWED, UNKNOWN }

    boolean isReady(long userId, long quotaGeneration, long bookId, long stockGeneration);
    Result reserveQuota(long userId, long generation, long requestId, long loanId, String token,
                        String requestHash, int maxActive, long expiresAtMillis);
    Result reserveStock(long bookId, long generation, long userId, long loanId, String token, long expiresAtMillis);
    Result confirmQuota(long userId, long generation, long loanId, String token);
    Result confirmStock(long bookId, long generation, long userId, long loanId, String token);
    Result cancelQuota(long userId, long generation, long loanId, String token);
    Result cancelStock(long bookId, long generation, long userId, long loanId, String token);
    Result releaseQuota(long userId, long generation, long loanId);
    Result releaseStock(long bookId, long generation, long userId, long loanId);
}
