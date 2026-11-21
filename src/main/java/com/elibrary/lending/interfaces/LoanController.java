package com.elibrary.lending.interfaces;

import com.elibrary.lending.application.LendingService;
import com.elibrary.lending.domain.ReturnReason;
import com.elibrary.shared.web.api.ApiHeaders;
import com.elibrary.shared.web.api.ApiPagination;
import com.elibrary.shared.web.api.Resp;
import com.elibrary.shared.web.security.UserContext;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供当前用户的借阅生命周期 API。所有资源查询都传入可信 userId，
 * 避免用户通过可猜测的 loanId 或 requestId 访问他人数据。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LoanController {
    private final LendingService service;

    /**
     * 创建借阅。Idempotency-Key 与 bookId 会持久化；相同请求重发时返回原 Loan。
     *
     * @param idempotencyKey 客户端提供的幂等键，相同键用于安全重发同一次借阅命令
     * @param request        经过字段验证的借阅书籍数据
     * @param response       HTTP 响应；重放已有结果时添加幂等重放响应头
     * @return 新建或重放的借阅结果统一响应
     */
    @PostMapping("/loans")
    public Resp<LendingDtos.LoanResponse> borrow(
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody LendingDtos.BorrowRequest request,
            HttpServletResponse response) {
        long userId = UserContext.require().getUserId();
        var result = service.borrow(userId, request.getBookId(), idempotencyKey);
        if (result.isReplay()) {
            response.setHeader(ApiHeaders.IDEMPOTENT_REPLAY, Boolean.TRUE.toString());
        }
        return Resp.success(result.getLoan());
    }

    /**
     * 查询属于当前用户的单笔 Loan。
     *
     * @param loanId 借阅标识码，必须为正整数
     * @return 指定借阅数据的统一响应
     */
    @GetMapping("/loans/{loanId}")
    public Resp<LendingDtos.LoanResponse> get(@PathVariable @Positive long loanId) {
        return Resp.success(service.getLoan(UserContext.require().getUserId(), loanId));
    }

    /**
     * 归还书籍；重复调用会返回第一次归还后的稳定结果。
     *
     * @param loanId 待归还的借阅标识码，必须为正整数
     * @return 归还后借阅数据的统一响应
     */
    @PostMapping("/loans/{loanId}/return")
    public Resp<LendingDtos.LoanResponse> returnLoan(@PathVariable @Positive long loanId) {
        return Resp.success(service.returnLoan(
                UserContext.require().getUserId(), loanId, ReturnReason.MANUAL));
    }

    /**
     * 根据 BORROWED 或 RETURNED 状态查询当前用户的借阅，结果按照借阅标识码递减排列。
     *
     * @param status      借阅状态；未提供时默认查询 BORROWED
     * @param afterLoanId 上一页最后一笔借阅标识码；未提供时从第一页开始
     * @param size        每页笔数，必须位于系统允许的分页范围内
     * @return 包含本页借阅、下一页标识码及是否仍有数据的统一响应
     */
    @GetMapping("/users/me/loans")
    public Resp<LendingDtos.LoanPage> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @Positive Long afterLoanId,
            @RequestParam(defaultValue = ApiPagination.DEFAULT_PAGE_SIZE)
            @Min(ApiPagination.MIN_PAGE_SIZE) @Max(ApiPagination.MAX_PAGE_SIZE) int size) {
        return Resp.success(service.list(
                UserContext.require().getUserId(), status, afterLoanId, size));
    }

}
