package com.elibrary.lending.infrastructure;

import com.elibrary.lending.domain.LoanStatus;
import com.elibrary.lending.domain.ReturnReason;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 借阅持久层契约。SQL 与动态条件统一维护于
 * {@code mybatis/lending/LendingMapper.xml}，介面只表达资料操作语义。
 */
@Mapper
public interface LendingMapper {
    // 使用者借阅帐户。
    int ensureAccount(@Param("userId") long userId, @Param("now") LocalDateTime now);

    Integer lockAccount(@Param("userId") long userId);

    int occupyQuota(@Param("userId") long userId, @Param("maxActive") int maxActive,
                    @Param("now") LocalDateTime now);

    int releaseQuota(@Param("userId") long userId, @Param("now") LocalDateTime now);

    Long accountVersion(@Param("userId") long userId);

    // 持久化幂等请求。
    int insertRequest(@Param("requestId") long requestId, @Param("userId") long userId,
                      @Param("idemKey") String idemKey, @Param("bookId") long bookId,
                      @Param("now") LocalDateTime now);

    LoanRow findLoanByIdempotencyKey(@Param("userId") long userId,
                                     @Param("idemKey") String idemKey);

    // 权威书籍库存。
    int initializeStock(@Param("bookId") long bookId, @Param("now") LocalDateTime now);

    int resizeStock(@Param("bookId") long bookId, @Param("newTotal") int newTotal,
                    @Param("now") LocalDateTime now);

    LendingStockRow findStock(@Param("bookId") long bookId);

    Integer lockStock(@Param("bookId") long bookId);

    int occupyStock(@Param("bookId") long bookId, @Param("now") LocalDateTime now);

    int releaseStock(@Param("bookId") long bookId, @Param("now") LocalDateTime now);

    Long stockVersion(@Param("bookId") long bookId);

    // 借阅生命周期与寻址分页查询。
    int insertLoan(LoanRow loan);

    LoanRow findLoan(@Param("loanId") long loanId, @Param("userId") long userId);

    LoanRow lockLoan(@Param("loanId") long loanId, @Param("userId") long userId);

    int returnLoan(@Param("loanId") long loanId, @Param("userId") long userId,
                   @Param("reason") ReturnReason reason,
                   @Param("expectedStatus") LoanStatus expectedStatus,
                   @Param("targetStatus") LoanStatus targetStatus,
                   @Param("now") LocalDateTime now);

    List<LoanRow> listLoans(@Param("userId") long userId, @Param("status") LoanStatus status,
                            @Param("afterLoanId") Long afterLoanId,
                            @Param("limit") int limit);

    List<LoanRow> findExpired(@Param("status") LoanStatus status,
                              @Param("now") LocalDateTime now, @Param("limit") int limit);
}
