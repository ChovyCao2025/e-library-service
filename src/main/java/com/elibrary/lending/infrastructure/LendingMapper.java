package com.elibrary.lending.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 借閱持久層契約。SQL 與動態條件統一維護於
 * {@code mybatis/lending/LendingMapper.xml}，Java 程式碼專注表達交易語義。
 */
@Mapper
public interface LendingMapper {
    // 資源帳戶與投影代次。
    int ensureAccount(@Param("userId") long userId, @Param("now") LocalDateTime now);

    int ensureUserProjection(@Param("userId") long userId, @Param("now") LocalDateTime now);

    ProjectionControlRow findProjection(@Param("type") String type, @Param("id") long id);

    ProjectionControlRow lockProjectionShare(@Param("type") String type, @Param("id") long id);

    ProjectionControlRow lockProjectionUpdate(@Param("type") String type, @Param("id") long id);

    int markDirtyFromReady(@Param("type") String type, @Param("id") long id, @Param("now") LocalDateTime now);

    // 持久化冪等臺帳與 attempt 狀態機。
    BorrowRequestRow findRequest(@Param("userId") long userId, @Param("idemKey") String idemKey);

    BorrowRequestRow findRequestById(@Param("requestId") long requestId, @Param("userId") long userId);

    BorrowRequestRow lockRequest(@Param("requestId") long requestId);

    int insertRequest(@Param("requestId") long requestId, @Param("userId") long userId,
                      @Param("idemKey") String idemKey, @Param("bookId") long bookId,
                      @Param("requestHash") String requestHash, @Param("loanId") long loanId,
                      @Param("attemptToken") String attemptToken, @Param("mode") String mode,
                      @Param("quotaGeneration") Long quotaGeneration, @Param("stockGeneration") Long stockGeneration,
                      @Param("leaseUntil") LocalDateTime leaseUntil, @Param("now") LocalDateTime now);

    int restartRequest(@Param("requestId") long requestId, @Param("token") String token,
                       @Param("mode") String mode, @Param("quotaGeneration") Long quotaGeneration,
                       @Param("stockGeneration") Long stockGeneration,
                       @Param("leaseUntil") LocalDateTime leaseUntil, @Param("now") LocalDateTime now);

    int markRequestSucceeded(@Param("requestId") long requestId, @Param("token") String token,
                             @Param("settled") boolean settled, @Param("now") LocalDateTime now);

    int markReleasing(@Param("requestId") long requestId, @Param("token") String token,
                      @Param("resultCode") String resultCode, @Param("now") LocalDateTime now);

    int markRetryable(@Param("requestId") long requestId, @Param("token") String token,
                      @Param("now") LocalDateTime now);

    int settleQuota(@Param("requestId") long requestId, @Param("token") String token,
                    @Param("now") LocalDateTime now);

    int settleStock(@Param("requestId") long requestId, @Param("token") String token,
                    @Param("now") LocalDateTime now);

    // 權威配額與庫存計數。
    Integer lockAccount(@Param("userId") long userId);

    int occupyQuota(@Param("userId") long userId, @Param("maxActive") int maxActive,
                    @Param("now") LocalDateTime now);

    int releaseQuota(@Param("userId") long userId, @Param("now") LocalDateTime now);

    Integer lockStock(@Param("bookId") long bookId);

    int occupyStock(@Param("bookId") long bookId, @Param("now") LocalDateTime now);

    int releaseStock(@Param("bookId") long bookId, @Param("now") LocalDateTime now);

    Long stockVersion(@Param("bookId") long bookId);

    Long accountVersion(@Param("userId") long userId);

    // 借閱生命週期與尋址分頁查詢。
    int insertLoan(@Param("loanId") long loanId, @Param("requestId") long requestId,
                   @Param("userId") long userId, @Param("bookId") long bookId,
                   @Param("idemKey") String idemKey, @Param("borrowedAt") LocalDateTime borrowedAt,
                   @Param("dueAt") LocalDateTime dueAt, @Param("policyVersion") String policyVersion);

    LoanRow findLoan(@Param("loanId") long loanId, @Param("userId") long userId);

    LoanRow lockLoan(@Param("loanId") long loanId, @Param("userId") long userId);

    LoanRow findLoanByRequest(@Param("requestId") long requestId);

    int returnLoan(@Param("loanId") long loanId, @Param("userId") long userId,
                   @Param("reason") String reason, @Param("now") LocalDateTime now);

    List<LoanRow> listLoans(@Param("userId") long userId, @Param("status") String status,
                            @Param("afterTime") LocalDateTime afterTime, @Param("afterId") Long afterId,
                            @Param("limit") int limit);

    List<LoanRow> findExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);

    List<BorrowRequestRow> findRequestsForRecovery(@Param("now") LocalDateTime now, @Param("limit") int limit);

    // 投影快照與租約式重建操作。
    int accountCount(@Param("userId") long userId);

    int stockTotal(@Param("bookId") long bookId);

    int stockAvailable(@Param("bookId") long bookId);

    List<LoanRow> activeLoansForUser(@Param("userId") long userId);

    List<LoanRow> activeLoansForBook(@Param("bookId") long bookId);

    List<ProjectionControlRow> findRebuildCandidates(@Param("now") LocalDateTime now, @Param("limit") int limit);

    int claimRebuild(@Param("type") String type, @Param("id") long id, @Param("owner") String owner,
                     @Param("leaseUntil") LocalDateTime leaseUntil, @Param("now") LocalDateTime now);

    int completeRebuild(@Param("type") String type, @Param("id") long id,
                        @Param("generation") long generation, @Param("owner") String owner,
                        @Param("now") LocalDateTime now);
}
